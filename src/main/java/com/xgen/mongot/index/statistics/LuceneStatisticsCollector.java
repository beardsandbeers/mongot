package com.xgen.mongot.index.statistics;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.index.definition.DocumentFieldDefinition;
import com.xgen.mongot.index.definition.EmbeddedDocumentsFieldDefinition;
import com.xgen.mongot.index.definition.HierarchicalFieldDefinition;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.index.definition.SearchIndexDefinition;
import com.xgen.mongot.index.lucene.codec.bloom.MongotBloomFieldsProducerRegistry;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.lucene.field.FieldValue;
import com.xgen.mongot.index.statistics.IndexStatistics.Bytes;
import com.xgen.mongot.index.statistics.IndexStatistics.Method;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.Accountable;

/** Bounded metadata-only statistics for independently pinned Lucene partitions. */
public final class LuceneStatisticsCollector {
  private static final int MAX_FIELDS = 100_000;
  private final Map<String, MutableField> fields = new TreeMap<>();
  private final Map<String, MutableRepresentation> physical = new HashMap<>();
  private final Map<Object, HeapResource> resources = new IdentityHashMap<>();
  private final Set<String> heapOmitted = new TreeSet<>();
  private final BooleanSupplier cancelled;
  private final IndexDefinition definition;
  private final boolean detailed;
  private boolean unrecognizedVector;

  private static final class MutableField {
    private final String path;
    private boolean configured;
    private boolean observed;
    private long disk;
    private long heap;
    private boolean heapKnown;
    private long vector;
    private boolean vectorComplete = true;
    private boolean vectorKnown;
    private final Map<String, MutableRepresentation> representations = new TreeMap<>();

    private MutableField(String path) {
      this.path = path;
    }
  }

  private static final class MutableRepresentation {
    private final String physical;
    private final String type;
    private final String multi;
    private final String root;
    private long disk;
    private long heap;
    private boolean heapKnown;
    private long vector;
    private boolean vectorComplete = true;
    private boolean vectorKnown;
    private final Map<String, Long> categories = new TreeMap<>();

    private MutableRepresentation(String physical, String type, String multi, String root) {
      this.physical = physical;
      this.type = type;
      this.multi = multi;
      this.root = root;
    }

    private String signature() {
      return this.type + "\0" + this.multi + "\0" + this.root;
    }
  }

  private static final class HeapResource {
    private final Accountable accountable;
    private final Set<String> paths = new TreeSet<>();
    private final Set<String> physicalNames = new TreeSet<>();
    private final Set<String> components = new TreeSet<>();

    private HeapResource(Accountable accountable) {
      this.accountable = accountable;
    }
  }

  private LuceneStatisticsCollector(
      IndexDefinition definition, boolean detailed, BooleanSupplier cancelled) {
    this.definition = definition;
    this.detailed = detailed;
    this.cancelled = cancelled;
  }

  public static IndexStatistics collect(
      LuceneStatisticsSnapshot snapshot,
      IndexDefinition definition,
      boolean detailed,
      OptionalLong writerHeap,
      BooleanSupplier cancelled)
      throws IOException {
    return new LuceneStatisticsCollector(definition, detailed, cancelled)
        .collect(snapshot, writerHeap);
  }

  private IndexStatistics collect(LuceneStatisticsSnapshot snapshot, OptionalLong writerHeap)
      throws IOException {
    long sampledAt = System.currentTimeMillis();
    if (this.detailed) {
      configuredFields();
    }
    var ledger = new StorageLedger();
    @Var long documents = 0;
    @Var long luceneDocuments = 0;
    @Var long deleted = 0;
    @Var long bloomHeap = 0;
    boolean nested =
        this.definition instanceof SearchIndexDefinition search
            ? search.hasEmbeddedFields()
            : this.definition.asVectorDefinition().getNestedRoot().isPresent();
    var partitions = snapshot.readers();
    for (int i = 0; i < partitions.size(); i++) {
      checkCancelled();
      var reader = partitions.get(i);
      luceneDocuments = Math.addExact(luceneDocuments, reader.numDocs());
      deleted = Math.addExact(deleted, (long) reader.maxDoc() - reader.numDocs());
      if (nested) {
        var searcher = new IndexSearcher(reader);
        searcher.setQueryCache(null);
        documents =
            Math.addExact(
                documents,
                searcher.count(
                    new TermQuery(
                        new Term(
                            FieldName.MetaField.EMBEDDED_ROOT.getLuceneFieldName(),
                            FieldValue.EMBEDDED_ROOT_FIELD_VALUE))));
      } else {
        documents = Math.addExact(documents, reader.numDocs());
      }
      for (var leaf : reader.leaves()) {
        checkCancelled();
        if (!(FilterLeafReader.unwrap(leaf.reader()) instanceof SegmentReader segment)) {
          throw new IOException("Statistics require a segment-backed Lucene reader");
        }
        for (var field : segment.getFieldInfos()) {
          if (this.detailed || field.getVectorDimension() > 0) {
            observe(field);
          }
        }
        if (this.detailed) {
          new LuceneStorageAttribution(segment, this::path, this::checkCancelled)
              .attribute(i, ledger);
        } else {
          for (String file : segment.getSegmentInfo().files()) {
            checkCancelled();
            ledger.file(new StorageLedger.File(i, file), segment.directory().fileLength(file));
          }
        }
        account(segment.getPostingsReader(), "postings", List.of());
        account(segment.getDocValuesReader(), "doc_values", List.of());
        account(segment.getNormsReader(), "norms", List.of());
        account(segment.getPointsReader(), "points", List.of());
        account(segment.getFieldsReader(), "stored_fields", List.of());
        account(segment.getTermVectorsReader(), "term_vectors", List.of());
        vectorMemory(segment);
        if (!(segment.getPostingsReader() instanceof Accountable)) {
          bloomHeap =
              Math.addExact(
                  bloomHeap, MongotBloomFieldsProducerRegistry.bloomStats(segment).heapBytes());
        }
      }
    }
    var memoryBuckets = new ArrayList<IndexStatistics.MemoryBucket>();
    long memoryAt = System.currentTimeMillis();
    @Var long heap = accountResources(memoryBuckets);
    heap = Math.addExact(heap, bloomHeap);
    memoryBuckets.add(
        new IndexStatistics.MemoryBucket(
            "system_id_bloom",
            List.of(),
            Bytes.measured(
                bloomHeap,
                Method.RESOURCE_ACCOUNTING,
                true,
                List.of("loaded_bloom_bitsets"),
                List.of())));
    @Var long vectorBytes = 0;
    @Var boolean vectorsComplete = !this.unrecognizedVector;
    @Var boolean vectorsKnown = false;
    for (var field : this.fields.values()) {
      vectorBytes = Math.addExact(vectorBytes, field.vector);
      vectorsComplete &= field.vectorComplete;
      vectorsKnown |= field.vectorKnown;
    }
    var memory =
        new IndexStatistics.Memory(
            Bytes.measured(
                heap,
                Method.RESOURCE_ACCOUNTING,
                false,
                List.of("accountable_codec_resources", "loaded_bloom_bitsets"),
                java.util.stream.Stream.concat(
                        this.heapOmitted.stream(),
                        java.util.stream.Stream.of(
                            "reader_metadata", "analyzers", "untracked_objects"))
                    .distinct()
                    .toList()),
            writerHeap.isPresent()
                ? Bytes.measured(
                    writerHeap.getAsLong(),
                    Method.RESOURCE_ACCOUNTING,
                    true,
                    List.of("lucene_writer_buffers"),
                    List.of())
                : Bytes.unavailable("writer buffer accounting unavailable"),
            Bytes.measured(
                snapshot.cacheHeapBytes(),
                Method.RESOURCE_ACCOUNTING,
                false,
                List.of("token_facet_ordinal_maps"),
                List.of("query_cache", "other_caches")),
            vector(vectorBytes, vectorsComplete, vectorsKnown));
    @Var long attributed = 0;
    @Var long shared = 0;
    @Var long unattributed = 0;
    Map<StorageLedger.Owner, Long> buckets = new HashMap<>();
    if (this.detailed) {
      for (var entry : ledger.entries()) {
        checkCancelled();
        var owner = entry.owner();
        if (!owner.path().isEmpty()) {
          var field = this.fields.get(owner.path());
          field.disk = Math.addExact(field.disk, entry.length());
          attributed = Math.addExact(attributed, entry.length());
          if (owner.physicalFields().size() == 1) {
            var representation = this.physical.get(owner.physicalFields().getFirst());
            representation.disk = Math.addExact(representation.disk, entry.length());
          }
        } else {
          buckets.merge(owner, entry.length(), Math::addExact);
          if (owner.unattributed()) {
            unattributed = Math.addExact(unattributed, entry.length());
          } else {
            shared = Math.addExact(shared, entry.length());
          }
        }
      }
    }
    var storage =
        buckets.entrySet().stream()
            .map(
                entry ->
                    new IndexStatistics.StorageBucket(
                        entry.getKey().component(),
                        entry.getKey().paths(),
                        entry.getValue(),
                        entry.getKey().unattributed(),
                        entry.getKey().reason()))
            .sorted(
                java.util.Comparator.comparing(IndexStatistics.StorageBucket::component)
                    .thenComparing(bucket -> String.join("\0", bucket.owners()))
                    .thenComparing(IndexStatistics.StorageBucket::reason))
            .toList();
    var directoryAt = System.currentTimeMillis();
    var directory = directorySize(partitions);
    return new IndexStatistics(
        sampledAt,
        documents,
        luceneDocuments,
        deleted,
        ledger.size(),
        directoryAt,
        directory,
        memoryAt,
        memory,
        this.detailed ? this.fields.values().stream().map(this::freeze).toList() : List.of(),
        storage,
        List.copyOf(memoryBuckets),
        attributed,
        shared,
        unattributed);
  }

  private MutableField field(String path) {
    if (!this.fields.containsKey(path) && this.fields.size() >= MAX_FIELDS) {
      throw new StatisticsLimitException("Field statistics exceed the field limit");
    }
    return this.fields.computeIfAbsent(path, MutableField::new);
  }

  private void observe(FieldInfo info) {
    String path = path(info.name);
    if (path.isEmpty()) {
      return;
    }
    var field = field(path);
    field.observed = true;
    this.physical.computeIfAbsent(
        info.name,
        name -> {
          String root =
              FieldName.EmbeddedField.isTypeOf(name)
                  ? FieldName.getEmbeddedRootPathOrThrow(name)
                  : "";
          String multi =
              FieldName.MultiField.isTypeOf(name)
                  ? FieldName.MultiField.getFieldPath(name).getMulti()
                  : "";
          @Var
          String type =
              FieldName.MultiField.isTypeOf(name)
                  ? "string"
                  : publicType(FieldName.TypeField.getTypeOf(name).orElseThrow());
          if (type.equals("vector") && this.definition instanceof SearchIndexDefinition search) {
            // Legacy knnVector is a distinct public mapping type, despite sharing storage families.
            var definition =
                search
                    .createFieldDefinitionResolver(
                        com.xgen.mongot.index.version.IndexFormatVersion.CURRENT)
                    .getFieldDefinition(
                        com.xgen.mongot.util.FieldPath.parse(path),
                        root.isEmpty()
                            ? Optional.empty()
                            : Optional.of(com.xgen.mongot.util.FieldPath.parse(root)));
            if (definition
                .flatMap(com.xgen.mongot.index.definition.FieldDefinition::knnVectorFieldDefinition)
                .isPresent()) {
              type = "knnVector";
            }
          }
          var representation = new MutableRepresentation(name, type, multi, root);
          field.representations.put(name, representation);
          return representation;
        });
  }

  private String path(String name) {
    if (FieldName.MultiField.isTypeOf(name)) {
      return FieldName.MultiField.getFieldPath(name).getFieldPath().toString();
    }
    return FieldName.TypeField.getTypeOf(name).map(type -> type.stripPrefix(name)).orElse("");
  }

  private void configuredFields() {
    if (this.definition instanceof SearchIndexDefinition search) {
      configuredDocument(search.getMappings(), "", "");
    } else {
      for (var definition : this.definition.asVectorDefinition().getFields()) {
        var field = field(definition.getPath().toString());
        field.configured = true;
        String type = definition.isVectorField() ? "vector" : "filter";
        var representation =
            new MutableRepresentation(
                "",
                type,
                "",
                this.definition
                    .asVectorDefinition()
                    .getNestedRoot()
                    .map(Object::toString)
                    .orElse(""));
        field.representations.put("configured:" + representation.signature(), representation);
      }
    }
  }

  private void configuredDocument(
      HierarchicalFieldDefinition document, String parent, String root) {
    for (var entry : document.fields().entrySet()) {
      checkCancelled();
      String path = parent.isEmpty() ? entry.getKey() : parent + "." + entry.getKey();
      var field = field(path);
      field.configured = true;
      for (var definition :
          entry.getValue().getAllDefinitions().flatMap(Optional::stream).toList()) {
        String type = definition.toBson().getString("type").getValue();
        var representation = new MutableRepresentation("", type, "", root);
        field.representations.put("configured:" + representation.signature(), representation);
        if (definition instanceof DocumentFieldDefinition child) {
          configuredDocument(child, path, root);
        }
        if (definition instanceof EmbeddedDocumentsFieldDefinition child) {
          configuredDocument(child, path, path);
        }
        if (definition instanceof com.xgen.mongot.index.definition.StringFieldDefinition string) {
          for (var multi : string.multi().keySet()) {
            var multiRepresentation = new MutableRepresentation("", "string", multi, root);
            field.representations.put(
                "configured:" + multiRepresentation.signature(), multiRepresentation);
          }
        }
      }
    }
  }

  private void vectorMemory(SegmentReader reader) {
    var vectors = reader.getVectorReader();
    if (vectors == null) {
      return;
    }
    for (var info : reader.getFieldInfos()) {
      if (info.getVectorDimension() == 0) {
        continue;
      }
      checkCancelled();
      var representation = this.physical.get(info.name);
      var field = this.fields.get(path(info.name));
      if (representation == null || field == null) {
        this.heapOmitted.add("unrecognized_vector_field");
        this.unrecognizedVector = true;
        continue;
      }
      @Var Map<String, Long> categories;
      try {
        categories = vectors.getOffHeapByteSize(info);
      } catch (UnsupportedOperationException e) {
        categories = Map.of();
      }
      if (categories.isEmpty()
          || categories.values().stream().anyMatch(value -> value == null || value < 0)) {
        representation.vectorComplete = false;
        field.vectorComplete = false;
      } else {
        representation.vectorKnown = true;
        field.vectorKnown = true;
        for (var category : categories.entrySet()) {
          representation.categories.merge(category.getKey(), category.getValue(), Math::addExact);
          representation.vector = Math.addExact(representation.vector, category.getValue());
          field.vector = Math.addExact(field.vector, category.getValue());
        }
      }
      KnnVectorsReader resource =
          vectors instanceof PerFieldKnnVectorsFormat.FieldsReader perField
              ? perField.getFieldReader(info.name)
              : vectors;
      account(resource, "vectors", List.of(info.name));
    }
  }

  private void account(Object object, String component, List<String> physicalFields) {
    if (object == null) {
      return;
    }
    if (object instanceof Accountable accountable) {
      var resource =
          this.resources.computeIfAbsent(object, ignored -> new HeapResource(accountable));
      resource.components.add(component);
      if (physicalFields.isEmpty()) {
        resource.paths.add("");
      }
      for (String name : physicalFields) {
        resource.paths.add(path(name));
        resource.physicalNames.add(name);
      }
    } else {
      this.heapOmitted.add(component);
    }
  }

  private long accountResources(List<IndexStatistics.MemoryBucket> buckets) {
    @Var long total = 0;
    for (var resource : this.resources.values()) {
      checkCancelled();
      long bytes = resource.accountable.ramBytesUsed();
      if (bytes < 0) {
        this.heapOmitted.addAll(resource.components);
        continue;
      }
      total = Math.addExact(total, bytes);
      if (resource.paths.size() == 1 && !resource.paths.contains("")) {
        var field = this.fields.get(resource.paths.iterator().next());
        field.heap = Math.addExact(field.heap, bytes);
        field.heapKnown = true;
        if (resource.physicalNames.size() == 1) {
          var representation = this.physical.get(resource.physicalNames.iterator().next());
          representation.heap = Math.addExact(representation.heap, bytes);
          representation.heapKnown = true;
        }
      } else {
        buckets.add(
            new IndexStatistics.MemoryBucket(
                String.join("+", resource.components),
                resource.paths.stream().filter(path -> !path.isEmpty()).toList(),
                Bytes.measured(
                    bytes,
                    Method.RESOURCE_ACCOUNTING,
                    false,
                    List.copyOf(resource.components),
                    List.of("exclusive_field_ownership_unavailable"))));
      }
    }
    return total;
  }

  private Bytes directorySize(List<DirectoryReader> readers) {
    @Var long total = 0;
    @Var boolean partial = false;
    @Var boolean observed = false;
    var directories =
        Collections.newSetFromMap(
            new IdentityHashMap<org.apache.lucene.store.Directory, Boolean>());
    for (var reader : readers) {
      checkCancelled();
      var directory = reader.directory();
      if (!directories.add(directory)) {
        continue;
      }
      try {
        for (String file : directory.listAll()) {
          checkCancelled();
          try {
            total = Math.addExact(total, directory.fileLength(file));
            observed = true;
          } catch (NoSuchFileException e) {
            partial = true;
          }
        }
        observed = true;
      } catch (IOException e) {
        partial = true;
      }
    }
    return observed
        ? Bytes.measured(
            total,
            Method.FILE_RANGE,
            !partial,
            List.of("generation_partition_files"),
            partial ? List.of("files_changed_or_unreadable_during_walk") : List.of())
        : Bytes.unavailable("generation directory sample unavailable");
  }

  private IndexStatistics.Field freeze(MutableField field) {
    var signatures =
        field.representations.values().stream()
            .filter(rep -> !rep.physical.isEmpty())
            .map(MutableRepresentation::signature)
            .collect(java.util.stream.Collectors.toSet());
    var representations =
        field.representations.values().stream()
            .filter(rep -> !rep.physical.isEmpty() || !signatures.contains(rep.signature()))
            .map(
                rep ->
                    new IndexStatistics.Representation(
                        rep.physical,
                        rep.type,
                        rep.multi,
                        rep.root,
                        disk(rep.disk, field.observed),
                        heap(rep.heap, rep.heapKnown, field.observed),
                        vector(rep.vector, rep.vectorComplete, rep.vectorKnown),
                        Map.copyOf(rep.categories)))
            .toList();
    return new IndexStatistics.Field(
        field.path,
        field.configured,
        field.observed,
        disk(field.disk, field.observed),
        heap(field.heap, field.heapKnown, field.observed),
        vector(field.vector, field.vectorComplete, field.vectorKnown),
        representations);
  }

  private static Bytes disk(long bytes, boolean observed) {
    return Bytes.measured(
        bytes,
        Method.FILE_RANGE,
        !observed,
        List.of("exclusive_codec_files_or_ranges"),
        observed ? List.of("shared_or_unsupported_storage") : List.of());
  }

  private static Bytes heap(long bytes, boolean known, boolean observed) {
    return known || !observed
        ? Bytes.measured(
            bytes,
            Method.RESOURCE_ACCOUNTING,
            !observed,
            List.of("field_owned_accountable_resources"),
            observed ? List.of("other_field_resources") : List.of())
        : Bytes.unavailable("field heap ownership unavailable");
  }

  private static Bytes vector(long bytes, boolean complete, boolean known) {
    if (!complete && !known) {
      return Bytes.unavailable("vector working-set accounting unavailable");
    }
    return Bytes.measured(
        bytes,
        Method.CODEC_WORKING_SET,
        complete,
        List.of("codec_vector_categories"),
        complete ? List.of() : List.of("unsupported_vector_reader"));
  }

  private static String publicType(FieldName.TypeField type) {
    return switch (type) {
      case AUTOCOMPLETE -> "autocomplete";
      case BOOLEAN -> "boolean";
      case DATE, DATE_V2, DATE_MULTIPLE -> "date";
      case DATE_FACET -> "dateFacet";
      case NUMBER_DOUBLE,
          NUMBER_DOUBLE_V2,
          NUMBER_DOUBLE_MULTIPLE,
          NUMBER_INT64,
          NUMBER_INT64_V2,
          NUMBER_INT64_MULTIPLE ->
          "number";
      case NUMBER_DOUBLE_FACET, NUMBER_INT64_FACET -> "numberFacet";
      case GEO_POINT, GEO_SHAPE -> "geo";
      case OBJECT_ID -> "objectId";
      case STRING -> "string";
      case TOKEN -> "token";
      case UUID -> "uuid";
      case SORTABLE_DATE_BETA_V1 -> "sortableDateBetaV1";
      case SORTABLE_NUMBER_BETA_V1 -> "sortableNumberBetaV1";
      case SORTABLE_STRING_BETA_V1 -> "sortableStringBetaV1";
      case NULL -> "null";
      case KNN_VECTOR, KNN_BYTE, KNN_BIT, KNN_F32_Q7, KNN_F32_Q1, KNN_BYTE_Q1 -> "vector";
    };
  }

  private void checkCancelled() {
    if (Thread.currentThread().isInterrupted() || this.cancelled.getAsBoolean()) {
      throw new CancellationException("Statistics collection was cancelled");
    }
  }
}
