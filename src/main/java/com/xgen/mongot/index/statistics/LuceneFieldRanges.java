package com.xgen.mongot.index.statistics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.DocValuesSkipIndexType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;

/**
 * Versioned metadata adapters for Lucene90 doc-values and points, used by both writable layouts.
 * Reads offsets/lengths only: never term dictionaries, postings, values, or vector payloads.
 */
final class LuceneFieldRanges {
  private final SegmentReader reader;
  private final Directory directory;
  private final BiFunction<String, List<FieldInfo>, StorageLedger.Owner> owner;
  private final Runnable checkCancelled;
  private final Map<String, List<StorageLedger.Entry>> plans = new HashMap<>();

  LuceneFieldRanges(
      SegmentReader reader,
      Directory directory,
      BiFunction<String, List<FieldInfo>, StorageLedger.Owner> owner,
      Runnable checkCancelled) {
    this.reader = reader;
    this.directory = directory;
    this.owner = owner;
    this.checkCancelled = checkCancelled;
  }

  Map<String, List<StorageLedger.Entry>> read(Set<String> files) throws IOException {
    for (String file : files) {
      this.checkCancelled.run();
      if (file.endsWith(".dvm")) {
        try {
          docValues(file);
        } catch (IOException | IllegalArgumentException e) {
          // Unknown versions stay with the format-level ownership group; never reuse a partial
          // plan.
        }
      }
    }
    String points = this.reader.getSegmentInfo().info.name + ".kdm";
    if (files.contains(points)) {
      try {
        points(points);
      } catch (IOException | IllegalArgumentException e) {
        /* Format-level fallback. */
      }
    }
    String norms = this.reader.getSegmentInfo().info.name + ".nvm";
    if (files.contains(norms)) {
      try {
        norms(norms);
      } catch (IOException | IllegalArgumentException e) {
        /* Format-level fallback. */
      }
    }
    return Map.copyOf(this.plans);
  }

  private void header(IndexInput input, String codec, int maximum, String suffix)
      throws IOException {
    CodecUtil.checkIndexHeader(
        input, codec, 0, maximum, this.reader.getSegmentInfo().info.getId(), suffix);
  }

  private void publish(StorageLedger ledger) {
    var grouped = new HashMap<String, List<StorageLedger.Entry>>();
    for (var entry : ledger.entries()) {
      grouped.computeIfAbsent(entry.file().name(), ignored -> new ArrayList<>()).add(entry);
    }
    grouped.forEach((file, entries) -> this.plans.put(file, List.copyOf(entries)));
  }

  private StorageLedger.File physical(StorageLedger ledger, String file, String component)
      throws IOException {
    var key = new StorageLedger.File(0, file);
    ledger.file(key, this.directory.fileLength(file));
    ledger.remainder(key, StorageLedger.Owner.system(component + "_shared_metadata_and_padding"));
    return key;
  }

  private void range(
      StorageLedger ledger,
      StorageLedger.File file,
      long offset,
      long length,
      String component,
      FieldInfo info) {
    ledger.claim(file, offset, length, this.owner.apply(component, List.of(info)));
  }

  private void docValues(String metaName) throws IOException {
    String base = metaName.substring(0, metaName.length() - 4);
    String segment = this.reader.getSegmentInfo().info.name;
    if (!base.startsWith(segment + "_")) {
      return;
    }
    String suffix = base.substring(segment.length() + 1);
    String dataName = base + ".dvd";
    var ledger = new StorageLedger();
    var metaFile = physical(ledger, metaName, "doc_values");
    var dataFile = physical(ledger, dataName, "doc_values");
    try (var meta = this.directory.openChecksumInput(metaName);
        var data = this.directory.openInput(dataName, IOContext.READONCE)) {
      header(meta, "Lucene90DocValuesMetadata", 0, suffix);
      header(data, "Lucene90DocValuesData", 0, suffix);
      CodecUtil.retrieveChecksum(data);
      var seen = new HashSet<Integer>();
      while (true) {
        this.checkCancelled.run();
        long start = meta.getFilePointer();
        int number = meta.readInt();
        if (number == -1) {
          break;
        }
        FieldInfo info = this.reader.getFieldInfos().fieldInfo(number);
        if (info == null || !seen.add(number)) {
          throw new IOException("Invalid doc-values field");
        }
        int type = meta.readByte();
        if (info.docValuesSkipIndexType() != DocValuesSkipIndexType.NONE) {
          pair(meta, ledger, dataFile, info);
          meta.readLong();
          meta.readLong();
          meta.readInt();
          meta.readInt();
        }
        switch (type) {
          case 0 -> numeric(meta, ledger, dataFile, info);
          case 1 -> binary(meta, ledger, dataFile, info);
          case 2 -> sorted(meta, ledger, dataFile, info);
          case 3 -> {
            int multi = meta.readByte();
            if (multi == 0) {
              sorted(meta, ledger, dataFile, info);
            } else if (multi == 1) {
              sortedNumeric(meta, ledger, dataFile, info);
              terms(meta, ledger, dataFile, info);
            } else {
              throw new IOException("Invalid sorted-set flag");
            }
          }
          case 4 -> sortedNumeric(meta, ledger, dataFile, info);
          default -> throw new IOException("Unsupported doc-values type");
        }
        range(ledger, metaFile, start, meta.getFilePointer() - start, "doc_values_metadata", info);
      }
      CodecUtil.checkFooter(meta);
      publish(ledger);
    }
  }

  private void pair(IndexInput meta, StorageLedger ledger, StorageLedger.File data, FieldInfo info)
      throws IOException {
    long offset = meta.readLong();
    long length = meta.readLong();
    if (offset >= 0) {
      range(ledger, data, offset, length, "doc_values", info);
    } else if ((offset != -1 && offset != -2) || length != 0) {
      throw new IOException("Invalid empty doc-values range");
    }
  }

  private long numeric(
      IndexInput meta, StorageLedger ledger, StorageLedger.File data, FieldInfo info)
      throws IOException {
    pair(meta, ledger, data, info);
    meta.readShort();
    meta.readByte();
    long values = meta.readLong();
    if (values < 0) {
      throw new IOException("Invalid numeric count");
    }
    int tableSize = meta.readInt();
    if (tableSize > 256 || tableSize < -64) {
      throw new IOException("Invalid numeric table");
    }
    if (tableSize >= 0) {
      meta.skipBytes((long) tableSize * Long.BYTES);
    }
    meta.readByte();
    meta.readLong();
    meta.readLong();
    pair(meta, ledger, data, info);
    meta.readLong(); // The jump table is already inside the numeric values range.
    return values;
  }

  private void binary(
      IndexInput meta, StorageLedger ledger, StorageLedger.File data, FieldInfo info)
      throws IOException {
    pair(meta, ledger, data, info);
    pair(meta, ledger, data, info);
    meta.readShort();
    meta.readByte();
    int docs = meta.readInt();
    int minimum = meta.readInt();
    int maximum = meta.readInt();
    if (docs < 0 || minimum < 0 || maximum < minimum) {
      throw new IOException("Invalid binary metadata");
    }
    if (minimum < maximum) {
      long offset = meta.readLong();
      monotonic(meta, (long) docs + 1, meta.readVInt());
      range(ledger, data, offset, meta.readLong(), "doc_values", info);
    }
  }

  private void sorted(
      IndexInput meta, StorageLedger ledger, StorageLedger.File data, FieldInfo info)
      throws IOException {
    numeric(meta, ledger, data, info);
    terms(meta, ledger, data, info);
  }

  private void sortedNumeric(
      IndexInput meta, StorageLedger ledger, StorageLedger.File data, FieldInfo info)
      throws IOException {
    long values = numeric(meta, ledger, data, info);
    int docs = meta.readInt();
    if (docs < 0 || docs > values) {
      throw new IOException("Invalid sorted-numeric count");
    }
    if (docs != values) {
      long offset = meta.readLong();
      monotonic(meta, (long) docs + 1, meta.readVInt());
      range(ledger, data, offset, meta.readLong(), "doc_values", info);
    }
  }

  private void terms(IndexInput meta, StorageLedger ledger, StorageLedger.File data, FieldInfo info)
      throws IOException {
    long size = meta.readVLong();
    int blockShift = meta.readInt();
    monotonic(meta, blocks(size, 6), blockShift);
    meta.readInt();
    meta.readInt();
    pair(meta, ledger, data, info);
    pair(meta, ledger, data, info);
    int indexShift = meta.readInt();
    monotonic(meta, Math.addExact(1, blocks(size, indexShift)), blockShift);
    pair(meta, ledger, data, info);
    pair(meta, ledger, data, info);
  }

  private static long blocks(long count, int shift) throws IOException {
    if (count < 0 || shift < 1 || shift > 30) {
      throw new IOException("Invalid monotonic shape");
    }
    return (count >>> shift) + ((count & ((1L << shift) - 1)) == 0 ? 0 : 1);
  }

  private void monotonic(IndexInput meta, long count, int shift) throws IOException {
    long blocks = blocks(count, shift);
    if (blocks > 1_000_000 || blocks > (meta.length() - meta.getFilePointer()) / 21) {
      throw new IOException("Monotonic metadata exceeds input budget");
    }
    meta.skipBytes(blocks * 21);
  }

  private record PointField(FieldInfo info, long dataStart, long indexStart, int indexLength) {}

  private void norms(String metaName) throws IOException {
    String dataName = metaName.substring(0, metaName.length() - 4) + ".nvd";
    var ledger = new StorageLedger();
    var metadata = physical(ledger, metaName, "norms");
    var values = physical(ledger, dataName, "norms");
    try (var meta = this.directory.openChecksumInput(metaName);
        var data = this.directory.openInput(dataName, IOContext.READONCE)) {
      header(meta, "Lucene90NormsMetadata", 0, "");
      header(data, "Lucene90NormsData", 0, "");
      CodecUtil.retrieveChecksum(data);
      var seen = new HashSet<Integer>();
      while (true) {
        this.checkCancelled.run();
        long start = meta.getFilePointer();
        int number = meta.readInt();
        if (number == -1) {
          break;
        }
        var info = this.reader.getFieldInfos().fieldInfo(number);
        if (info == null || !info.hasNorms() || !seen.add(number)) {
          throw new IOException("Invalid norms field");
        }
        long docsOffset = meta.readLong();
        long docsLength = meta.readLong();
        meta.readShort();
        meta.readByte();
        int docs = meta.readInt();
        int width = meta.readByte();
        long offset = meta.readLong();
        if (docs < 0 || docs > this.reader.maxDoc() || !Set.of(0, 1, 2, 4, 8).contains(width)) {
          throw new IOException("Invalid norms shape");
        }
        if (docsOffset >= 0) {
          range(ledger, values, docsOffset, docsLength, "norms", info);
        } else if ((docsOffset != -1 && docsOffset != -2) || docsLength != 0) {
          throw new IOException("Invalid norms document range");
        }
        if (width > 0) {
          range(ledger, values, offset, (long) docs * width, "norms", info);
        }
        range(ledger, metadata, start, meta.getFilePointer() - start, "norms_metadata", info);
      }
      CodecUtil.checkFooter(meta);
      publish(ledger);
    }
  }

  private void points(String metaName) throws IOException {
    String base = metaName.substring(0, metaName.length() - 4);
    var ledger = new StorageLedger();
    var metaFile = physical(ledger, metaName, "points");
    var indexFile = physical(ledger, base + ".kdi", "points");
    var dataFile = physical(ledger, base + ".kdd", "points");
    var fields = new ArrayList<PointField>();
    try (var meta = this.directory.openChecksumInput(metaName);
        var index = this.directory.openInput(indexFile.name(), IOContext.READONCE);
        var data = this.directory.openInput(dataFile.name(), IOContext.READONCE)) {
      header(meta, "Lucene90PointsFormatMeta", 1, "");
      header(index, "Lucene90PointsFormatIndex", 1, "");
      header(data, "Lucene90PointsFormatData", 1, "");
      long dataHeader = data.getFilePointer();
      long indexHeader = index.getFilePointer();
      var seen = new HashSet<Integer>();
      while (true) {
        this.checkCancelled.run();
        long start = meta.getFilePointer();
        int number = meta.readInt();
        if (number == -1) {
          break;
        }
        var info = this.reader.getFieldInfos().fieldInfo(number);
        if (info == null || !seen.add(number)) {
          throw new IOException("Invalid point field");
        }
        CodecUtil.checkHeader(meta, "BKD", 9, 10);
        int dims = meta.readVInt();
        int indexed = meta.readVInt();
        int maximumLeaf = meta.readVInt();
        int width = meta.readVInt();
        int leaves = meta.readVInt();
        if (dims != info.getPointDimensionCount()
            || indexed != info.getPointIndexDimensionCount()
            || width != info.getPointNumBytes()
            || maximumLeaf <= 0
            || leaves <= 0) {
          throw new IOException("Unsupported point shape");
        }
        meta.skipBytes(2L * indexed * width);
        long pointCount = meta.readVLong();
        int docs = meta.readVInt();
        int indexLength = meta.readVInt();
        long dataStart = meta.readLong();
        long indexStart = meta.readLong();
        if (pointCount <= 0
            || docs <= 0
            || docs > pointCount
            || dataStart < dataHeader
            || indexStart < indexHeader
            || indexLength < 0) {
          throw new IOException("Invalid point ranges");
        }
        fields.add(new PointField(info, dataStart, indexStart, indexLength));
        range(ledger, metaFile, start, meta.getFilePointer() - start, "points_metadata", info);
      }
      long indexLength = meta.readLong();
      long dataLength = meta.readLong();
      CodecUtil.checkFooter(meta);
      CodecUtil.retrieveChecksum(index, indexLength);
      CodecUtil.retrieveChecksum(data, dataLength);
      fields.sort(Comparator.comparingLong(PointField::dataStart));
      for (int i = 0; i < fields.size(); i++) {
        var field = fields.get(i);
        long end =
            i + 1 < fields.size()
                ? fields.get(i + 1).dataStart()
                : dataLength - CodecUtil.footerLength();
        range(ledger, dataFile, field.dataStart(), end - field.dataStart(), "points", field.info());
        range(
            ledger,
            indexFile,
            field.indexStart(),
            field.indexLength(),
            "points_index",
            field.info());
      }
      publish(ledger);
    }
  }
}
