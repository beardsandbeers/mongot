package com.xgen.mongot.index.statistics;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.xgen.mongot.index.definition.VectorFieldSpecification;
import com.xgen.mongot.index.definition.VectorIndexingAlgorithm;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import com.xgen.mongot.index.lucene.codec.LuceneCodec;
import com.xgen.mongot.util.FieldPath;
import com.xgen.testing.mongot.index.definition.SearchIndexDefinitionBuilder;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class LuceneStatisticsCollectorTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();
  private final LuceneCodec.WriteFormat format;
  private final boolean compound;

  @Parameterized.Parameters(name = "format={0}, compound={1}")
  public static List<Object[]> configurations() {
    return List.of(
        new Object[] {LuceneCodec.WriteFormat.LUCENE99, false},
        new Object[] {LuceneCodec.WriteFormat.LUCENE99, true},
        new Object[] {LuceneCodec.WriteFormat.LUCENE104, false},
        new Object[] {LuceneCodec.WriteFormat.LUCENE104, true});
  }

  public LuceneStatisticsCollectorTest(LuceneCodec.WriteFormat format, boolean compound) {
    this.format = format;
    this.compound = compound;
  }

  @Test
  public void collect_supportedCodecAndCompoundFiles_reconcilesStorageAndVectorMemory()
      throws Exception {
    try (var directory = FSDirectory.open(this.folder.getRoot().toPath());
        var writer = new IndexWriter(directory, config())) {
      writer.addDocument(document("a"));
      writer.addDocument(document("b"));
      try (var reader = DirectoryReader.open(writer);
          var snapshot = LuceneStatisticsSnapshot.retain(reader, 27)) {

        var stats =
            LuceneStatisticsCollector.collect(
                snapshot,
                SearchIndexDefinitionBuilder.VALID_INDEX,
                true,
                OptionalLong.of(123),
                () -> false);

        assertThat(
                ((SegmentReader) reader.leaves().getFirst().reader())
                    .getSegmentInfo()
                    .info
                    .getUseCompoundFile())
            .isEqualTo(this.compound);
        assertThat(stats.documentCount()).isEqualTo(2);
        assertThat(stats.luceneDocumentCount()).isEqualTo(2);
        assertThat(stats.indexSizeBytes()).isGreaterThan(0);
        assertThat(
                stats.attributedDiskBytes()
                    + stats.sharedDiskBytes()
                    + stats.unattributedDiskBytes())
            .isEqualTo(stats.indexSizeBytes());
        assertThat(
                stats.fields().stream()
                    .mapToLong(field -> field.disk().value().orElseThrow())
                    .sum())
            .isEqualTo(stats.attributedDiskBytes());
        var embedding =
            stats.fields().stream()
                .filter(field -> field.path().equals("embedding"))
                .findFirst()
                .orElseThrow();
        assertThat(embedding.disk().value().orElseThrow()).isGreaterThan(0);
        assertThat(embedding.desiredVectorOffHeap().value().orElseThrow()).isGreaterThan(0);
        assertThat(embedding.desiredVectorOffHeap().method())
            .isEqualTo(IndexStatistics.Method.CODEC_WORKING_SET);
        assertThat(embedding.readerHeap().value().orElseThrow()).isGreaterThan(0);
        assertThat(stats.memory().writerBufferHeap().value().orElseThrow()).isEqualTo(123);
        assertThat(stats.memory().cacheHeap().value().orElseThrow()).isEqualTo(27);
        assertThat(stats.memory().cacheHeap().completeness())
            .isEqualTo(IndexStatistics.Completeness.PARTIAL);
        assertThat(stats.directorySize().value().orElseThrow()).isAtLeast(stats.indexSizeBytes());
      }
    }
  }

  @Test
  public void collect_vectorEncodingsAndAlgorithms_matchesCodecWorkingSetCategories()
      throws Exception {
    var paths = List.of("float", "byte", "bit", "scalar", "binary");
    var flat =
        new VectorFieldSpecification(
            128,
            VectorSimilarity.EUCLIDEAN,
            VectorQuantization.NONE,
            new VectorIndexingAlgorithm.FlatIndexingAlgorithm());
    var fields =
        paths.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    path -> FieldPath.parse(path + "Flat"), path -> flat));
    try (var directory = FSDirectory.open(this.folder.getRoot().toPath());
        var writer =
            new IndexWriter(directory, config().setCodec(new LuceneCodec(this.format, fields)))) {
      writer.addDocuments(
          java.util.stream.IntStream.range(0, 32)
              .mapToObj(LuceneStatisticsCollectorTest::vectorDocument)
              .toList());
      try (var reader = DirectoryReader.open(writer);
          var snapshot = LuceneStatisticsSnapshot.retain(reader, 0)) {

        var stats =
            LuceneStatisticsCollector.collect(
                snapshot,
                SearchIndexDefinitionBuilder.VALID_INDEX,
                true,
                OptionalLong.empty(),
                () -> false);

        assertThat(stats.fields()).hasSize(10);
        var segment = (SegmentReader) reader.leaves().getFirst().reader();
        stats.fields().forEach(field -> assertVectorWorkingSet(field, segment));
        assertThat(
                stats.attributedDiskBytes()
                    + stats.sharedDiskBytes()
                    + stats.unattributedDiskBytes())
            .isEqualTo(stats.indexSizeBytes());
      }
    }
  }

  private static void assertVectorWorkingSet(IndexStatistics.Field field, SegmentReader segment) {
    var representation = field.representations().getFirst();
    Map<String, Long> categories =
        segment
            .getVectorReader()
            .getOffHeapByteSize(segment.getFieldInfos().fieldInfo(representation.physicalName()));
    assertThat(representation.vectorCategories()).containsExactlyEntriesIn(categories);
    if (categories.isEmpty()) {
      assertThat(field.desiredVectorOffHeap().value().isPresent()).isFalse();
    } else {
      assertThat(field.desiredVectorOffHeap().value().orElseThrow())
          .isEqualTo(categories.values().stream().mapToLong(Long::longValue).sum());
    }
  }

  private static Document vectorDocument(int id) {
    var document = new Document();
    document.add(new StringField("$meta/_id", Integer.toString(id), Field.Store.NO));
    float[] floats = new float[128];
    java.util.Arrays.fill(floats, id + 1);
    byte[] bytes = new byte[16];
    java.util.Arrays.fill(bytes, (byte) id);
    for (String suffix : List.of("", "Flat")) {
      document.add(new KnnFloatVectorField("$type:knnVector/float" + suffix, floats));
      document.add(new KnnByteVectorField("$type:knnByte/byte" + suffix, bytes));
      document.add(new KnnByteVectorField("$type:knnBit/bit" + suffix, bytes));
      document.add(new KnnFloatVectorField("$type:knnF32Q7/scalar" + suffix, floats));
      document.add(new KnnFloatVectorField("$type:knnF32Q1/binary" + suffix, floats));
    }
    return document;
  }

  @Test
  public void collect_sharedDocValuesAndPoints_attributesIndividualFieldsFromMetadata()
      throws Exception {
    try (var directory = FSDirectory.open(this.folder.getRoot().toPath());
        var writer = new IndexWriter(directory, config())) {
      writer.addDocuments(
          java.util.stream.IntStream.range(0, 100)
              .mapToObj(LuceneStatisticsCollectorTest::numericDocument)
              .toList());
      try (var reader = DirectoryReader.open(writer);
          var snapshot = LuceneStatisticsSnapshot.retain(reader, 0)) {

        var stats =
            LuceneStatisticsCollector.collect(
                snapshot,
                SearchIndexDefinitionBuilder.VALID_INDEX,
                true,
                OptionalLong.empty(),
                () -> false);

        var first =
            stats.fields().stream()
                .filter(field -> field.path().equals("first"))
                .findFirst()
                .orElseThrow();
        var second =
            stats.fields().stream()
                .filter(field -> field.path().equals("second"))
                .findFirst()
                .orElseThrow();
        var blob =
            stats.fields().stream()
                .filter(field -> field.path().equals("blob"))
                .findFirst()
                .orElseThrow();
        assertThat(first.disk().value().orElseThrow()).isGreaterThan(0);
        assertThat(second.disk().value().orElseThrow()).isGreaterThan(0);
        assertThat(blob.disk().value().orElseThrow())
            .isGreaterThan(first.disk().value().orElseThrow());
        assertThat(
                stats.storageBuckets().stream()
                    .filter(
                        bucket -> bucket.component().equals("points") && bucket.owners().size() > 1)
                    .toList())
            .isEmpty();
        assertThat(
                stats.storageBuckets().stream()
                    .filter(
                        bucket ->
                            bucket.component().equals("doc_values") && bucket.owners().size() > 1)
                    .toList())
            .isEmpty();
        assertThat(
                stats.storageBuckets().stream()
                    .filter(
                        bucket -> bucket.component().equals("norms") && bucket.owners().size() > 1)
                    .toList())
            .isEmpty();
        assertThat(stats.attributedDiskBytes()).isGreaterThan(stats.indexSizeBytes() / 2);
        assertThat(
                stats.attributedDiskBytes()
                    + stats.sharedDiskBytes()
                    + stats.unattributedDiskBytes())
            .isEqualTo(stats.indexSizeBytes());
      }
    }
  }

  @Test
  public void collect_manySegments_reconcilesAcrossRetainedFilesAndLaterUpdates() throws Exception {
    try (var directory = FSDirectory.open(this.folder.getRoot().toPath());
        var writer = new IndexWriter(directory, config())) {
      populateSegments(writer);
      try (var reader = DirectoryReader.open(writer);
          var snapshot = LuceneStatisticsSnapshot.retain(reader, 0)) {
        writer.updateNumericDocValue(new Term("$meta/_id", "0"), "$type:int64/update", 999);
        writer.commit();

        var stats =
            LuceneStatisticsCollector.collect(
                snapshot,
                SearchIndexDefinitionBuilder.VALID_INDEX,
                true,
                OptionalLong.empty(),
                () -> false);

        assertThat(reader.leaves()).hasSize(24);
        assertThat(stats.documentCount()).isEqualTo(24);
        assertThat(
                stats.attributedDiskBytes()
                    + stats.sharedDiskBytes()
                    + stats.unattributedDiskBytes())
            .isEqualTo(stats.indexSizeBytes());
        assertThat(stats.attributedDiskBytes()).isGreaterThan(0);
      }
    }
  }

  private static void populateSegments(IndexWriter writer) throws java.io.IOException {
    for (int id = 0; id < 24; id++) {
      writer.addDocument(numericDocument(id));
      writer.flush();
    }
  }

  @Test
  public void collect_liveAndDeletedDocuments_usesPinnedReadersInsteadOfWriterCounts()
      throws Exception {
    try (var directory = FSDirectory.open(this.folder.getRoot().toPath());
        var writer = new IndexWriter(directory, config())) {
      writer.addDocument(document("a"));
      writer.addDocument(document("b"));
      writer.flush();
      writer.deleteDocuments(new Term("$meta/_id", "b"));
      try (var reader = DirectoryReader.open(writer);
          var snapshot = LuceneStatisticsSnapshot.retain(reader, 0)) {
        writer.addDocument(document("c"));

        var stats =
            LuceneStatisticsCollector.collect(
                snapshot,
                SearchIndexDefinitionBuilder.VALID_INDEX,
                false,
                OptionalLong.empty(),
                () -> false);

        assertThat(stats.documentCount()).isEqualTo(1);
        assertThat(stats.deletedLuceneDocumentCount()).isEqualTo(1);
        assertThat(stats.fields()).isEmpty();
        assertThat(stats.memory().writerBufferHeap().value().isPresent()).isFalse();
      }
    }
  }

  @Test
  public void collect_cancelled_releasesRetainedReaderWhenSnapshotCloses() throws Exception {
    try (var directory = FSDirectory.open(this.folder.getRoot().toPath());
        var writer = new IndexWriter(directory, config());
        var reader = DirectoryReader.open(writer)) {
      var snapshot = LuceneStatisticsSnapshot.retain(reader, 0);

      assertThrows(
          CancellationException.class,
          () ->
              LuceneStatisticsCollector.collect(
                  snapshot,
                  SearchIndexDefinitionBuilder.VALID_INDEX,
                  true,
                  OptionalLong.empty(),
                  () -> true));
      snapshot.close();

      assertThat(reader.getRefCount()).isEqualTo(1);
      snapshot.close();
      assertThat(reader.getRefCount()).isEqualTo(1);
    }
  }

  private IndexWriterConfig config() {
    return new IndexWriterConfig(new KeywordAnalyzer())
        .setCodec(new LuceneCodec(this.format))
        .setUseCompoundFile(this.compound)
        .setMergePolicy(NoMergePolicy.INSTANCE);
  }

  private static Document numericDocument(int id) {
    var document = new Document();
    document.add(new StringField("$meta/_id", Integer.toString(id), Field.Store.NO));
    document.add(new TextField("$type:string/title", "coffee ".repeat(id + 1), Field.Store.NO));
    document.add(
        new TextField("$type:string/summary", "summary ".repeat(id % 7 + 1), Field.Store.NO));
    document.add(new org.apache.lucene.document.LongPoint("$type:int64/first", id));
    document.add(
        new org.apache.lucene.document.LongPoint("$type:int64/second", (long) id * 100_000));
    document.add(new org.apache.lucene.document.NumericDocValuesField("$type:int64/first", id));
    document.add(new org.apache.lucene.document.NumericDocValuesField("$type:int64/update", id));
    document.add(
        new org.apache.lucene.document.NumericDocValuesField(
            "$type:int64/second", (long) id * 100_000));
    document.add(
        new org.apache.lucene.document.BinaryDocValuesField(
            "$type:string/blob", new org.apache.lucene.util.BytesRef("data".repeat(256) + id)));
    document.add(
        new org.apache.lucene.document.SortedDocValuesField(
            "$type:token/sorted", new org.apache.lucene.util.BytesRef("tag" + id)));
    document.add(
        new org.apache.lucene.document.SortedSetDocValuesField(
            "$type:token/tags", new org.apache.lucene.util.BytesRef("tag" + id)));
    document.add(
        new org.apache.lucene.document.SortedSetDocValuesField(
            "$type:token/tags", new org.apache.lucene.util.BytesRef("other" + id)));
    document.add(
        new org.apache.lucene.document.SortedNumericDocValuesField("$type:doubleV2/multi", id));
    document.add(
        new org.apache.lucene.document.SortedNumericDocValuesField(
            "$type:doubleV2/multi", id + 1000));
    return document;
  }

  private static Document document(String id) {
    var document = new Document();
    document.add(new StringField("$meta/_id", id, Field.Store.NO));
    document.add(new TextField("$type:string/title", "coffee", Field.Store.YES));
    document.add(new KnnFloatVectorField("$type:knnVector/embedding", new float[] {1, 0, 0}));
    return document;
  }
}
