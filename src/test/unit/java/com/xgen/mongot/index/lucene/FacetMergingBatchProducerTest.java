package com.xgen.mongot.index.lucene;

import static com.xgen.mongot.util.Check.checkState;

import com.xgen.mongot.cursor.CursorConfig;
import com.xgen.mongot.cursor.batch.BatchCursorOptions;
import com.xgen.mongot.index.BatchProducer;
import com.xgen.mongot.index.CountResult;
import com.xgen.mongot.index.FacetBucket;
import com.xgen.mongot.index.FacetInfo;
import com.xgen.mongot.index.IntermediateFacetBucket;
import com.xgen.mongot.index.MetaResults;
import com.xgen.mongot.index.query.collectors.FacetCollector;
import com.xgen.mongot.index.query.collectors.FacetDefinition;
import com.xgen.mongot.index.query.counts.Count;
import com.xgen.mongot.util.Bytes;
import com.xgen.testing.mongot.cursor.batch.BatchCursorOptionsBuilder;
import com.xgen.testing.mongot.index.query.collectors.CollectorBuilder;
import com.xgen.testing.mongot.index.query.collectors.FacetDefinitionBuilder;
import com.xgen.testing.mongot.index.query.operators.OperatorBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.bson.BsonArray;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.Assert;
import org.junit.Test;

public class FacetMergingBatchProducerTest {
  private static FacetCollector getFacetCollector() {
    // Use LinkedHashMap here to make the map iteration order deterministic.
    Map<String, FacetDefinition> facetDefinitions = new LinkedHashMap<>();
    facetDefinitions.put(
        "brand", FacetDefinitionBuilder.string().numBuckets(8).path("brandPath").build());
    facetDefinitions.put(
        "facetNoHit", FacetDefinitionBuilder.string().numBuckets(8).path("brandPath").build());
    facetDefinitions.put(
        "price",
        FacetDefinitionBuilder.numeric()
            .boundaries(
                List.of(new BsonInt32(10), new BsonInt32(21), new BsonInt32(31), new BsonInt32(41)))
            .defaultBucketName("defaultBucket")
            .path("pricePath")
            .build());
    return CollectorBuilder.facet()
        .operator(OperatorBuilder.exists().path("_id").build())
        .facetDefinitions(facetDefinitions)
        .build();
  }

  private static final FacetCollector FACET_COLLECTOR = getFacetCollector();

  @Test
  public void testWrappingSingleProducerYieldsIdenticalResults() throws Exception {
    FacetMergingBatchProducer wrappedProducer =
        FacetMergingBatchProducer.create(List.of(createSingleProducer1()));

    LuceneFacetCollectorMetaBatchProducer batchProducer2 = createSingleProducer1();
    Assert.assertEquals(getAllBatches(batchProducer2), getAllBatches(wrappedProducer));
  }

  @Test
  public void testMergingMultipleProducers() throws Exception {
    FacetMergingBatchProducer mergingBatchProducer =
        FacetMergingBatchProducer.create(
            List.of(createSingleProducer1(), createSingleProducer2(), createSingleProducer3()));
    var mergedResults = getAllBatches(mergingBatchProducer);

    LuceneFacetCollectorMetaBatchProducer expectedBatchProducer =
        createSingleProducerForMergedResults();
    var expectedResults = getAllBatches(expectedBatchProducer);
    Assert.assertEquals(expectedResults, mergedResults);
  }

  @Test
  public void testGetMetaResults() throws Exception {
    FacetMergingBatchProducer mergingBatchProducer =
        FacetMergingBatchProducer.create(
            List.of(createSingleProducer1(), createSingleProducer2(), createSingleProducer3()));
    MetaResults metaResults = mergingBatchProducer.getMetaResultsAndClose(Count.Type.LOWER_BOUND);
    Assert.assertEquals(CountResult.lowerBoundCount(606), metaResults.count());
    Map<String, FacetInfo> facet = metaResults.facet().get();
    List<FacetBucket> brandBuckets =
        List.of(
            new FacetBucket(new BsonString("tieBreakA"), 1000),
            new FacetBucket(new BsonString("tieBreakB"), 1000),
            new FacetBucket(new BsonString("nike"), 110),
            new FacetBucket(new BsonString("puma"), 50),
            new FacetBucket(new BsonString("lining"), 20),
            new FacetBucket(new BsonString("zz"), 14),
            new FacetBucket(new BsonString("adidas"), 11),
            new FacetBucket(new BsonString("xxxx"), 10));
    Assert.assertEquals(brandBuckets, facet.get("brand").buckets());

    List<FacetBucket> priceBuckets =
        List.of(
            new FacetBucket(new BsonInt64(10), 144),
            new FacetBucket(new BsonInt64(21), 80),
            new FacetBucket(new BsonInt64(31), 113),
            new FacetBucket(new BsonInt64(41), 104),
            new FacetBucket(new BsonString("defaultBucket"), 3003));
    Assert.assertEquals(priceBuckets, facet.get("price").buckets());

    Assert.assertTrue(facet.get("facetNoHit").buckets().isEmpty());
  }

  private LuceneFacetCollectorMetaBatchProducer createSingleProducer1() {
    Queue<LuceneMetaBucketProducer> bucketProducers = new LinkedList<>();
    List<IntermediateFacetBucket> buckets = new ArrayList<>();
    // Intentionally oder by string facet value.
    buckets.add(getStringFacetBucket("brand", "adidas", 11));
    buckets.add(getStringFacetBucket("brand", "lining", 9));
    buckets.add(getStringFacetBucket("brand", "nike", 100));
    buckets.add(getStringFacetBucket("brand", "puma", 50));
    buckets.add(getStringFacetBucket("brand", "tieBreakA", 999));
    buckets.add(getStringFacetBucket("brand", "tieBreakB", 1000));

    buckets.add(getNumericFacetBucket("price", 10, 100));
    buckets.add(getNumericFacetBucket("price", 21, 50));
    buckets.add(getNumericFacetBucket("price", 31, 10));
    buckets.add(getNumericFacetBucket("price", 41, 9));
    buckets.add(
        new IntermediateFacetBucket(
            IntermediateFacetBucket.Type.FACET, "price", new BsonString("defaultBucket"), 1001));
    bucketProducers.add(new MockProducer(buckets));
    return new LuceneFacetCollectorMetaBatchProducer(100, bucketProducers, FACET_COLLECTOR);
  }

  private LuceneFacetCollectorMetaBatchProducer createSingleProducer2() {
    Queue<LuceneMetaBucketProducer> bucketProducers = new LinkedList<>();
    List<IntermediateFacetBucket> buckets = new ArrayList<>();
    // Intentionally oder by string facet value.
    buckets.add(getStringFacetBucket("brand", "nike", 1));
    buckets.add(getStringFacetBucket("brand", "tieBreakA", 1));
    buckets.add(getStringFacetBucket("brand", "xxxx", 9));
    buckets.add(getStringFacetBucket("brand", "yyy", 8));
    buckets.add(getStringFacetBucket("brand", "zz", 7));

    buckets.add(getNumericFacetBucket("price", 10, 43));
    buckets.add(getNumericFacetBucket("price", 21, 28));
    buckets.add(getNumericFacetBucket("price", 31, 100));
    buckets.add(getNumericFacetBucket("price", 41, 91));
    buckets.add(
        new IntermediateFacetBucket(
            IntermediateFacetBucket.Type.FACET, "price", new BsonString("defaultBucket"), 1001));
    bucketProducers.add(new MockProducer(buckets));
    return new LuceneFacetCollectorMetaBatchProducer(201, bucketProducers, FACET_COLLECTOR);
  }

  private LuceneFacetCollectorMetaBatchProducer createSingleProducer3() {
    Queue<LuceneMetaBucketProducer> bucketProducers = new LinkedList<>();
    List<IntermediateFacetBucket> buckets = new ArrayList<>();
    // Intentionally oder by string facet value.
    buckets.add(getStringFacetBucket("brand", "aaa", 1));
    buckets.add(getStringFacetBucket("brand", "lining", 11));
    buckets.add(getStringFacetBucket("brand", "nike", 9));
    buckets.add(getStringFacetBucket("brand", "xxxx", 1));
    buckets.add(getStringFacetBucket("brand", "zz", 7));

    buckets.add(getNumericFacetBucket("price", 10, 1));
    buckets.add(getNumericFacetBucket("price", 21, 2));
    buckets.add(getNumericFacetBucket("price", 31, 3));
    buckets.add(getNumericFacetBucket("price", 41, 4));
    buckets.add(
        new IntermediateFacetBucket(
            IntermediateFacetBucket.Type.FACET, "price", new BsonString("defaultBucket"), 1001));
    bucketProducers.add(new MockProducer(buckets));
    return new LuceneFacetCollectorMetaBatchProducer(305, bucketProducers, FACET_COLLECTOR);
  }

  private LuceneFacetCollectorMetaBatchProducer createSingleProducerForMergedResults() {
    Queue<LuceneMetaBucketProducer> bucketProducers = new LinkedList<>();
    List<IntermediateFacetBucket> buckets = new ArrayList<>();
    // Intentionally oder by string facet value.
    buckets.add(getStringFacetBucket("brand", "aaa", 1));
    buckets.add(getStringFacetBucket("brand", "adidas", 11));
    buckets.add(getStringFacetBucket("brand", "lining", 20));
    buckets.add(getStringFacetBucket("brand", "nike", 110));
    buckets.add(getStringFacetBucket("brand", "puma", 50));
    buckets.add(getStringFacetBucket("brand", "tieBreakA", 1000));
    buckets.add(getStringFacetBucket("brand", "tieBreakB", 1000));
    buckets.add(getStringFacetBucket("brand", "xxxx", 10));
    buckets.add(getStringFacetBucket("brand", "yyy", 8));
    buckets.add(getStringFacetBucket("brand", "zz", 14));

    buckets.add(getNumericFacetBucket("price", 10, 144));
    buckets.add(getNumericFacetBucket("price", 21, 80));
    buckets.add(getNumericFacetBucket("price", 31, 113));
    buckets.add(getNumericFacetBucket("price", 41, 104));
    buckets.add(
        new IntermediateFacetBucket(
            IntermediateFacetBucket.Type.FACET, "price", new BsonString("defaultBucket"), 3003));
    bucketProducers.add(new MockProducer(buckets));
    return new LuceneFacetCollectorMetaBatchProducer(606, bucketProducers, FACET_COLLECTOR);
  }

  private List<BsonArray> getAllBatches(BatchProducer batchProducer) throws IOException {
    List<BsonArray> ret = new ArrayList<>();
    Bytes sizeLimit = CursorConfig.DEFAULT_BSON_SIZE_SOFT_LIMIT;
    BatchCursorOptions cursorOptions = BatchCursorOptionsBuilder.empty();
    while (!batchProducer.isExhausted()) {
      batchProducer.execute(sizeLimit, cursorOptions);
      ret.add(batchProducer.getNextBatch(sizeLimit));
    }
    // After exhausted, calling an extra getNextBatch() will return empty.
    Assert.assertTrue(
        batchProducer.getNextBatch(CursorConfig.DEFAULT_BSON_SIZE_SOFT_LIMIT).isEmpty());
    return ret;
  }

  private static IntermediateFacetBucket getStringFacetBucket(
      String tag, String facetValue, long facetCount) {
    return new IntermediateFacetBucket(
        IntermediateFacetBucket.Type.FACET, tag, new BsonString(facetValue), facetCount);
  }

  private static IntermediateFacetBucket getNumericFacetBucket(
      String tag, long facetValue, long facetCount) {
    return new IntermediateFacetBucket(
        IntermediateFacetBucket.Type.FACET, tag, new BsonInt64(facetValue), facetCount);
  }

  private static class MockProducer implements LuceneMetaBucketProducer {
    private int position;

    private final List<IntermediateFacetBucket> values;

    public MockProducer(List<IntermediateFacetBucket> buckets) {
      this.position = 0;
      this.values = buckets;
    }

    @Override
    public IntermediateFacetBucket peek() {
      checkState(this.position < this.values.size(), "Producer is exhausted.");
      return this.values.get(this.position);
    }

    @Override
    public void acceptAndAdvance() {
      checkState(this.position < this.values.size(), "Producer is exhausted.");
      this.position++;
    }

    @Override
    public boolean isExhausted() {
      return this.position >= this.values.size();
    }
  }
}
