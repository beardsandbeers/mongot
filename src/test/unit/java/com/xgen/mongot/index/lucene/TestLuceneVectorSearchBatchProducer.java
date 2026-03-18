package com.xgen.mongot.index.lucene;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.cursor.batch.AdjustableBatchSizeStrategy;
import com.xgen.mongot.cursor.batch.BatchCursorOptions;
import com.xgen.mongot.cursor.batch.BatchSizeStrategy;
import com.xgen.mongot.cursor.batch.ConstantBatchSizeStrategy;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.VectorSearchResult;
import com.xgen.mongot.util.Bytes;
import com.xgen.testing.mongot.cursor.batch.BatchCursorOptionsBuilder;
import com.xgen.testing.mongot.index.IndexMetricsUpdaterBuilder;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.Assert;
import org.junit.Test;

public class TestLuceneVectorSearchBatchProducer {

  @Test
  public void testDocumentCannotFitInBatch() throws IOException {
    var producer =
        createProducer(
            List.of(createVectorResult(1, "small")),
            new ConstantBatchSizeStrategy(Integer.MAX_VALUE));

    var ex =
        Assert.assertThrows(
            IllegalStateException.class, () -> producer.getNextBatch(Bytes.ofBytes(8)));

    Assert.assertEquals("Search result output exceeds BSON size limit", ex.getMessage());
  }

  @Test
  public void testMultipleBatchesMakeProgressUntilExhausted() throws IOException {
    List<VectorSearchResult> results =
        List.of(
            createVectorResult(1, "doc-1"),
            createVectorResult(2, "doc-2"),
            createVectorResult(3, "doc-3"));

    var producer = createProducer(results, new ConstantBatchSizeStrategy(Integer.MAX_VALUE));
    Bytes sizeLimit = limitThatFitsExactlyOneResult(results.getFirst(), results.get(1));

    @Var int totalDocsReturned = 0;
    @Var int batchCount = 0;
    while (!producer.isExhausted()) {
      var batch = producer.getNextBatch(sizeLimit);
      Assert.assertTrue(batch.size() > 0);
      Assert.assertEquals(1, batch.size());
      totalDocsReturned += batch.size();
      batchCount++;
    }

    Assert.assertEquals(3, totalDocsReturned);
    Assert.assertEquals(3, batchCount);
  }

  @Test
  public void testNextBatchAfterExhaustedIsEmpty() throws IOException {
    var producer =
        createProducer(
            List.of(createVectorResult(1, "one")),
            new ConstantBatchSizeStrategy(Integer.MAX_VALUE));

    var firstBatch = producer.getNextBatch(Bytes.ofMebi(16));
    Assert.assertEquals(1, firstBatch.size());
    Assert.assertTrue(producer.isExhausted());

    var secondBatch = producer.getNextBatch(Bytes.ofMebi(16));
    Assert.assertTrue(secondBatch.isEmpty());
  }

  @Test
  public void testBatchSizeStrategyCapsBatch() throws IOException {
    var producer =
        createProducer(
            List.of(
                createVectorResult(1, "one"),
                createVectorResult(2, "two"),
                createVectorResult(3, "three")),
            new ConstantBatchSizeStrategy(2));

    producer.execute(Bytes.ofMebi(16), BatchCursorOptions.empty());
    var firstBatch = producer.getNextBatch(Bytes.ofMebi(16));
    Assert.assertEquals(2, firstBatch.size());
    Assert.assertFalse(producer.isExhausted());

    producer.execute(Bytes.ofMebi(16), BatchCursorOptions.empty());
    var secondBatch = producer.getNextBatch(Bytes.ofMebi(16));
    Assert.assertEquals(1, secondBatch.size());
    Assert.assertTrue(producer.isExhausted());
  }

  @Test
  public void testDocsRequestedAffectsBatchSizeAcrossBatches() throws IOException {
    List<VectorSearchResult> results =
        List.of(
            createVectorResult(1, "doc-1"),
            createVectorResult(2, "doc-2"),
            createVectorResult(3, "doc-3"),
            createVectorResult(4, "doc-4"),
            createVectorResult(5, "doc-5"),
            createVectorResult(6, "doc-6"),
            createVectorResult(7, "doc-7"),
            createVectorResult(8, "doc-8"),
            createVectorResult(9, "doc-9"),
            createVectorResult(10, "doc-10"),
            createVectorResult(11, "doc-11"),
            createVectorResult(12, "doc-12"),
            createVectorResult(13, "doc-13"),
            createVectorResult(14, "doc-14"),
            createVectorResult(15, "doc-15"),
            createVectorResult(16, "doc-16"),
            createVectorResult(17, "doc-17"),
            createVectorResult(18, "doc-18"),
            createVectorResult(19, "doc-19"),
            createVectorResult(20, "doc-20"),
            createVectorResult(21, "doc-21"),
            createVectorResult(22, "doc-22"),
            createVectorResult(23, "doc-23"),
            createVectorResult(24, "doc-24"),
            createVectorResult(25, "doc-25"),
            createVectorResult(26, "doc-26"),
            createVectorResult(27, "doc-27"),
            createVectorResult(28, "doc-28"),
            createVectorResult(29, "doc-29"),
            createVectorResult(30, "doc-30"),
            createVectorResult(31, "doc-31"),
            createVectorResult(32, "doc-32"),
            createVectorResult(33, "doc-33"),
            createVectorResult(34, "doc-34"),
            createVectorResult(35, "doc-35"),
            createVectorResult(36, "doc-36"),
            createVectorResult(37, "doc-37"),
            createVectorResult(38, "doc-38"),
            createVectorResult(39, "doc-39"),
            createVectorResult(40, "doc-40"));

    var strategy =
        AdjustableBatchSizeStrategy.create(
            BatchCursorOptionsBuilder.builder().docsRequested(25).build(), true);
    var producer = createProducer(results, strategy);

    // Simulates the cursor's first request where docsRequested is 25.
    producer.execute(Bytes.ofMebi(16), BatchCursorOptions.empty());
    var firstBatch = producer.getNextBatch(Bytes.ofMebi(16));
    Assert.assertEquals(25, firstBatch.size());
    Assert.assertFalse(producer.isExhausted());

    // Simulates getMore lowering docsRequested to 10.
    strategy.adjust(BatchCursorOptionsBuilder.builder().docsRequested(10).build());
    producer.execute(Bytes.ofMebi(16), BatchCursorOptions.empty());
    var secondBatch = producer.getNextBatch(Bytes.ofMebi(16));
    Assert.assertEquals(10, secondBatch.size());
    Assert.assertFalse(producer.isExhausted());
  }

  private static LuceneVectorSearchBatchProducer createProducer(
      List<VectorSearchResult> results, BatchSizeStrategy batchSizeStrategy) {
    IndexMetricsUpdater.QueryingMetricsUpdater updater =
        IndexMetricsUpdaterBuilder.QueryingMetricsUpdaterBuilder.empty();
    return new LuceneVectorSearchBatchProducer(results, updater, batchSizeStrategy);
  }

  private static VectorSearchResult createVectorResult(int id, String payloadValue) {
    var payload = new BsonDocument("payload", new BsonInt32(payloadValue.hashCode()));
    return new VectorSearchResult(new BsonInt32(id), 1.0f, Optional.of(payload));
  }

  /**
   * Creates a limit that allows exactly one of the two results to fit in a batch.
   *
   * <p>This mirrors {@code BsonArrayBuilder} accounting:
   *
   * <ul>
   *   <li>5 bytes array overhead
   *   <li>3 bytes per element overhead for single-digit array indexes
   * </ul>
   */
  private static Bytes limitThatFitsExactlyOneResult(
      VectorSearchResult firstResult, VectorSearchResult secondResult) {
    int arrayOverhead = 5;
    int elementOverhead = 3;
    int firstSize = firstResult.toRawBson().getByteBuffer().remaining();
    int secondSize = secondResult.toRawBson().getByteBuffer().remaining();
    int maxSingle = arrayOverhead + elementOverhead + firstSize;
    int minDouble = maxSingle + elementOverhead + secondSize;
    return Bytes.ofBytes(minDouble - 1);
  }
}
