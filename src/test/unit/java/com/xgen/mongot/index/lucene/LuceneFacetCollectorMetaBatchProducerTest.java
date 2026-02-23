package com.xgen.mongot.index.lucene;

import static com.xgen.mongot.util.Check.checkState;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.cursor.CursorConfig;
import com.xgen.mongot.index.IntermediateFacetBucket;
import com.xgen.mongot.index.query.collectors.FacetCollector;
import com.xgen.mongot.util.BsonUtils;
import com.xgen.mongot.util.Bytes;
import com.xgen.testing.mongot.cursor.batch.BatchCursorOptionsBuilder;
import com.xgen.testing.mongot.index.query.collectors.FacetCollectorBuilder;
import com.xgen.testing.mongot.index.query.collectors.FacetDefinitionBuilder;
import com.xgen.testing.mongot.index.query.operators.OperatorBuilder;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.Assert;
import org.junit.Test;

public class LuceneFacetCollectorMetaBatchProducerTest {

  private static final int NUM_COUNT_DOCS = 1;

  private static final BsonDocument COUNT_BUCKET_DOC =
      new BsonDocument("type", new BsonString("count")).append("count", new BsonInt64(1));

  private static final FacetCollector FACET_COLLECTOR =
      FacetCollectorBuilder.facet()
          .operator(OperatorBuilder.exists().path("_id").build())
          .facetDefinitions(Map.of("facet", FacetDefinitionBuilder.string().path("facet").build()))
          .build();

  @Test
  public void testSingleProducer() throws Exception {
    int numBuckets = 2;
    Queue<LuceneMetaBucketProducer> bucketProducers = new LinkedList<>();
    bucketProducers.add(new MockProducer(numBuckets));

    LuceneFacetCollectorMetaBatchProducer batchProducer =
        new LuceneFacetCollectorMetaBatchProducer(
            numBuckets + NUM_COUNT_DOCS, bucketProducers, FACET_COLLECTOR);

    assertBatchesProduced(
        batchProducer, CursorConfig.DEFAULT_BSON_SIZE_SOFT_LIMIT, numBuckets + NUM_COUNT_DOCS, 1);
  }

  @Test
  public void testMultipleProducers() throws Exception {
    int numBuckets = 2;
    Queue<LuceneMetaBucketProducer> bucketProducers = new LinkedList<>();
    bucketProducers.add(new MockProducer(numBuckets));
    bucketProducers.add(new MockProducer(numBuckets));

    LuceneFacetCollectorMetaBatchProducer batchProducer =
        new LuceneFacetCollectorMetaBatchProducer(
            2 * numBuckets + NUM_COUNT_DOCS, bucketProducers, FACET_COLLECTOR);

    assertBatchesProduced(
        batchProducer,
        CursorConfig.DEFAULT_BSON_SIZE_SOFT_LIMIT,
        2 * numBuckets + NUM_COUNT_DOCS,
        1);
  }

  @Test
  public void testSingleProducerMultipleBatches() throws Exception {
    int numBuckets = 2;
    Queue<LuceneMetaBucketProducer> bucketProducers = new LinkedList<>();
    bucketProducers.add(new MockProducer(numBuckets));

    LuceneFacetCollectorMetaBatchProducer batchProducer =
        new LuceneFacetCollectorMetaBatchProducer(
            numBuckets + NUM_COUNT_DOCS, bucketProducers, FACET_COLLECTOR);

    var bsonArray = new BsonArray();
    bsonArray.add(COUNT_BUCKET_DOC);
    bsonArray.add(getBucket().toBson());
    Bytes sizeLimitForOneBucket = BsonUtils.bsonValueSerializedBytes(bsonArray);

    // The sizeLimit can contain 1 bucket, but there are 2 buckets in total.
    assertBatchesProduced(batchProducer, sizeLimitForOneBucket, numBuckets + NUM_COUNT_DOCS, 2);
  }

  @Test
  public void testMultipleProducersMultipleBatches() throws Exception {
    int numBuckets = 2;
    Queue<LuceneMetaBucketProducer> bucketProducers = new LinkedList<>();
    bucketProducers.add(new MockProducer(numBuckets));
    bucketProducers.add(new MockProducer(numBuckets));

    LuceneFacetCollectorMetaBatchProducer batchProducer =
        new LuceneFacetCollectorMetaBatchProducer(
            2 * numBuckets + NUM_COUNT_DOCS, bucketProducers, FACET_COLLECTOR);

    var bsonArray = new BsonArray();
    bsonArray.add(COUNT_BUCKET_DOC);
    bsonArray.add(getBucket().toBson());
    bsonArray.add(getBucket().toBson());
    Bytes sizeLimitForTwoBuckets = BsonUtils.bsonValueSerializedBytes(bsonArray);

    // The sizeLimit can contain 2 buckets, but there are 4 buckets in total.
    assertBatchesProduced(
        batchProducer, sizeLimitForTwoBuckets, 2 * numBuckets + NUM_COUNT_DOCS, 2);
  }

  private void assertBatchesProduced(
      LuceneFacetCollectorMetaBatchProducer batchProducer,
      Bytes resultsSizeLimit,
      int expectedCount,
      int expectedNumBatches)
      throws Exception {
    @Var boolean countChecked = false;
    @Var int docCount = 0;
    @Var int batchCount = 0;
    while (!batchProducer.isExhausted()) {
      batchProducer.execute(resultsSizeLimit, BatchCursorOptionsBuilder.empty());
      BsonArray batch = batchProducer.getNextBatch(resultsSizeLimit);
      if (!countChecked) {
        Assert.assertEquals(
            new BsonString("count"), batch.getValues().get(0).asDocument().get("type"));
        Assert.assertEquals(
            new BsonInt64(expectedCount), batch.getValues().get(0).asDocument().get("count"));
        countChecked = true;
      }

      batchCount++;
      docCount += batch.getValues().size();
    }

    Assert.assertEquals(expectedCount, docCount);
    Assert.assertEquals(expectedNumBatches, batchCount);
  }

  private static IntermediateFacetBucket getBucket() {
    return new IntermediateFacetBucket(
        IntermediateFacetBucket.Type.FACET, "tag", new BsonInt64(1), 100);
  }

  private static class MockProducer implements LuceneMetaBucketProducer {
    private final int numBuckets;

    private int position;

    public MockProducer(int numBuckets) {
      this.numBuckets = numBuckets;

      this.position = 0;
    }

    @Override
    public IntermediateFacetBucket peek() {
      checkState(this.position < this.numBuckets, "Producer is exhausted.");
      return getBucket();
    }

    @Override
    public void acceptAndAdvance() {
      checkState(this.position < this.numBuckets, "Producer is exhausted.");

      this.position++;
    }

    @Override
    public boolean isExhausted() {
      return this.position >= this.numBuckets;
    }
  }
}
