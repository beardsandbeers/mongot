package com.xgen.mongot.index.statistics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.perfield.PerFieldDocValuesFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldPostingsFormat;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.store.IOContext;

/** Reads ownership metadata, never postings, stored values or vector payloads. */
final class LuceneStorageAttribution {
  private record CompoundEntry(String name, long offset, long length) {}

  private final SegmentReader reader;
  private final Function<String, String> path;
  private final Map<String, List<FieldInfo>> formats = new HashMap<>();
  private final Map<String, String> components = new HashMap<>();
  private final Runnable checkCancelled;
  private Map<String, List<StorageLedger.Entry>> fieldRanges = Map.of();

  LuceneStorageAttribution(
      SegmentReader reader, Function<String, String> path, Runnable checkCancelled) {
    this.reader = reader;
    this.path = path;
    this.checkCancelled = checkCancelled;
    for (var field : reader.getFieldInfos()) {
      addFormat(
          this.formats,
          this.components,
          field,
          "postings",
          PerFieldPostingsFormat.PER_FIELD_FORMAT_KEY,
          PerFieldPostingsFormat.PER_FIELD_SUFFIX_KEY);
      addFormat(
          this.formats,
          this.components,
          field,
          "doc_values",
          PerFieldDocValuesFormat.PER_FIELD_FORMAT_KEY,
          PerFieldDocValuesFormat.PER_FIELD_SUFFIX_KEY);
      addFormat(
          this.formats,
          this.components,
          field,
          "vectors",
          PerFieldKnnVectorsFormat.PER_FIELD_FORMAT_KEY,
          PerFieldKnnVectorsFormat.PER_FIELD_SUFFIX_KEY);
    }
  }

  void attribute(int partition, StorageLedger ledger) throws IOException {
    var info = this.reader.getSegmentInfo();
    var directory = info.info.dir;
    try {
      if (info.info.getUseCompoundFile()) {
        try (var compound =
            info.info.getCodec().compoundFormat().getCompoundReader(directory, info.info)) {
          this.fieldRanges =
              new LuceneFieldRanges(this.reader, compound, this::group, this.checkCancelled)
                  .read(Set.of(compound.listAll()));
        }
      } else {
        this.fieldRanges =
            new LuceneFieldRanges(this.reader, directory, this::group, this.checkCancelled)
                .read(Set.copyOf(info.files()));
      }
    } catch (IOException | IllegalArgumentException e) {
      this.fieldRanges = Map.of();
    }
    for (String name : info.files()) {
      this.checkCancelled.run();
      var file = new StorageLedger.File(partition, name);
      long length = directory.fileLength(name);
      ledger.file(file, length);
      if (name.endsWith(".cfs")) {
        compound(file, length, ledger);
      } else {
        virtual(file, 0, length, name, ledger);
      }
    }
  }

  private void compound(StorageLedger.File file, long length, StorageLedger ledger) {
    var info = this.reader.getSegmentInfo().info;
    // Both writable layouts in this repository use this versioned compound format. Other
    // layouts are deliberately reported as unavailable, rather than guessed by file suffix.
    if (!info.getCodec()
        .compoundFormat()
        .getClass()
        .getSimpleName()
        .equals("Lucene90CompoundFormat")) {
      ledger.invalidate(file, "unsupported compound format");
      return;
    }
    try (var input = info.dir.openChecksumInput(info.name + ".cfe")) {
      CodecUtil.checkIndexHeader(input, "Lucene90CompoundEntries", 0, 0, info.getId(), "");
      int count = input.readVInt();
      if (count < 0 || count > 1_000_000 || count > input.length() / 17) {
        throw new IOException("Invalid compound entry count");
      }
      var entries = new ArrayList<CompoundEntry>();
      Set<String> names = new HashSet<>();
      for (int i = 0; i < count; i++) {
        this.checkCancelled.run();
        String name = input.readString();
        long offset = input.readLong();
        long bytes = input.readLong();
        if (!names.add(name)
            || offset < CodecUtil.indexHeaderLength("Lucene90CompoundData", "")
            || bytes < 0
            || offset > length - CodecUtil.footerLength()
            || bytes > length - CodecUtil.footerLength() - offset) {
          throw new IOException("Invalid compound entry range");
        }
        entries.add(new CompoundEntry(info.name + name, offset, bytes));
      }
      CodecUtil.checkFooter(input);
      try (var data = info.dir.openInput(file.name(), IOContext.READONCE)) {
        CodecUtil.checkIndexHeader(data, "Lucene90CompoundData", 0, 0, info.getId(), "");
        CodecUtil.retrieveChecksum(data);
      }
      ledger.remainder(file, StorageLedger.Owner.system("compound_container_overhead"));
      for (var entry : entries) {
        virtual(file, entry.offset(), entry.length(), entry.name(), ledger);
      }
    } catch (IOException | IllegalArgumentException e) {
      ledger.invalidate(file, "compound ownership metadata unavailable or invalid");
    }
  }

  private void virtual(
      StorageLedger.File physical, long base, long length, String name, StorageLedger ledger) {
    var plan = this.fieldRanges.get(name);
    if (plan == null) {
      ledger.claim(physical, base, length, owner(name));
    } else if (plan.stream().mapToLong(StorageLedger.Entry::length).reduce(0, Math::addExact)
        != length) {
      ledger.claim(
          physical,
          base,
          length,
          StorageLedger.Owner.unknown("virtual file length disagrees with codec metadata"));
    } else {
      for (var entry : plan) {
        this.checkCancelled.run();
        ledger.claim(physical, Math.addExact(base, entry.offset()), entry.length(), entry.owner());
      }
    }
  }

  private StorageLedger.Owner owner(String file) {
    String extension = IndexFileNames.getExtension(file);
    if (Set.of("si", "fnm", "cfe", "liv").contains(extension)) {
      return StorageLedger.Owner.system("segment_metadata_and_live_docs");
    }
    if (Set.of("fdt", "fdx", "fdm").contains(extension)) {
      return StorageLedger.Owner.system("shared_stored_fields_and_source");
    }
    if (Set.of("tvd", "tvx", "tvm").contains(extension)) {
      return group("term_vectors", fields(FieldInfo::hasTermVectors));
    }
    if (Set.of("nvd", "nvm").contains(extension)) {
      return group("norms", fields(FieldInfo::hasNorms));
    }
    if (Set.of("kdd", "kdi", "kdm", "dim", "dii").contains(extension)) {
      return group("points", fields(field -> field.getPointDimensionCount() > 0));
    }
    String prefix = this.reader.getSegmentInfo().info.name + "_";
    int dot = file.indexOf('.');
    String suffix =
        file.startsWith(prefix) && dot > prefix.length()
            ? file.substring(prefix.length(), dot)
            : "";
    var match = this.formats.get(suffix);
    if (match != null) {
      // Update files append a generation suffix to their segment suffix. They are not attributed
      // through a loose prefix match; unsupported layouts remain explicit.
      return group(this.components.get(suffix), match);
    }
    return StorageLedger.Owner.unknown("no exclusive owner from supported codec metadata");
  }

  private void addFormat(
      Map<String, List<FieldInfo>> groups,
      Map<String, String> components,
      FieldInfo field,
      String component,
      String formatKey,
      String suffixKey) {
    String format = field.getAttribute(formatKey);
    String suffix = field.getAttribute(suffixKey);
    if (format != null && suffix != null) {
      String key = format + "_" + suffix;
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(field);
      components.put(key, component);
    }
  }

  private List<FieldInfo> fields(java.util.function.Predicate<FieldInfo> predicate) {
    var result = new ArrayList<FieldInfo>();
    for (var field : this.reader.getFieldInfos()) {
      if (predicate.test(field)) {
        result.add(field);
      }
    }
    return result;
  }

  private StorageLedger.Owner group(String component, List<FieldInfo> fields) {
    if (fields.isEmpty()) {
      return StorageLedger.Owner.unknown("file has no matching field metadata");
    }
    var allPaths =
        fields.stream().map(field -> this.path.apply(field.name)).distinct().sorted().toList();
    String exclusive = allPaths.size() == 1 ? allPaths.getFirst() : "";
    return new StorageLedger.Owner(
        exclusive,
        component,
        allPaths.stream().filter(path -> !path.isEmpty()).toList(),
        fields.stream().map(field -> field.name).distinct().sorted().toList(),
        false,
        exclusive.isEmpty()
            ? "format resource is shared by multiple fields or system metadata"
            : "");
  }
}
