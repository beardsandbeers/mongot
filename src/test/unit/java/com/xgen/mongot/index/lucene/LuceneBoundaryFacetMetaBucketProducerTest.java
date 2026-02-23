package com.xgen.mongot.index.lucene;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.index.IntermediateFacetBucket;
import com.xgen.mongot.index.query.collectors.FacetDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.LabelAndValue;
import org.bson.BsonDateTime;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNumber;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.Assert;
import org.junit.Test;

public class LuceneBoundaryFacetMetaBucketProducerTest {

  private record DefaultBucketInfo(String bucketName, long bucketCount) {}

  private static final int DEFAULT_COUNT = 5;

  @Test
  public void testNoDefaultInt32() {
    int numBuckets = 5;
    FacetResult facetResult = getFacetResult(numBuckets);
    List<BsonNumber> boundaries = getNumberBoundaries(BsonInt32::new, numBuckets);

    FacetDefinition.NumericFacetDefinition facetDefinition =
        new FacetDefinition.NumericFacetDefinition("myPath", Optional.empty(), boundaries);
    LuceneBoundaryFacetMetaBucketProducer metaProducer =
        LuceneBoundaryFacetMetaBucketProducer.create(
            facetResult, "myFacet", facetDefinition, facetResult.value.longValue());

    assertBucketCounts(metaProducer, boundaries, facetResult.labelValues, Optional.empty());
  }

  @Test
  public void testDefaultInt32() {
    int numBuckets = 5;
    FacetResult facetResult = getFacetResult(numBuckets);
    List<BsonNumber> boundaries = getNumberBoundaries(BsonInt32::new, numBuckets);

    FacetDefinition.NumericFacetDefinition facetDefinition =
        new FacetDefinition.NumericFacetDefinition("myPath", Optional.of("default"), boundaries);
    LuceneBoundaryFacetMetaBucketProducer metaProducer =
        LuceneBoundaryFacetMetaBucketProducer.create(
            facetResult, "myFacet", facetDefinition, facetResult.value.longValue() + DEFAULT_COUNT);

    assertBucketCounts(
        metaProducer,
        boundaries,
        facetResult.labelValues,
        Optional.of(new DefaultBucketInfo("default", DEFAULT_COUNT)));
  }

  @Test
  public void testNoDefaultInt64() {
    int numBuckets = 5;
    FacetResult facetResult = getFacetResult(numBuckets);
    List<BsonNumber> boundaries = getNumberBoundaries(BsonInt64::new, numBuckets);

    FacetDefinition.NumericFacetDefinition facetDefinition =
        new FacetDefinition.NumericFacetDefinition("myPath", Optional.empty(), boundaries);
    LuceneBoundaryFacetMetaBucketProducer metaProducer =
        LuceneBoundaryFacetMetaBucketProducer.create(
            facetResult, "myFacet", facetDefinition, facetResult.value.longValue());

    assertBucketCounts(metaProducer, boundaries, facetResult.labelValues, Optional.empty());
  }

  @Test
  public void testDefaultIn64() {
    int numBuckets = 5;
    FacetResult facetResult = getFacetResult(numBuckets);
    List<BsonNumber> boundaries = getNumberBoundaries(BsonInt64::new, numBuckets);

    FacetDefinition.NumericFacetDefinition facetDefinition =
        new FacetDefinition.NumericFacetDefinition("myPath", Optional.of("default"), boundaries);
    LuceneBoundaryFacetMetaBucketProducer metaProducer =
        LuceneBoundaryFacetMetaBucketProducer.create(
            facetResult, "myFacet", facetDefinition, facetResult.value.longValue() + DEFAULT_COUNT);

    assertBucketCounts(
        metaProducer,
        boundaries,
        facetResult.labelValues,
        Optional.of(new DefaultBucketInfo("default", DEFAULT_COUNT)));
  }

  @Test
  public void testNoDefaultDouble() {
    int numBuckets = 5;
    FacetResult facetResult = getFacetResult(numBuckets);
    List<BsonNumber> boundaries = getNumberBoundaries(BsonDouble::new, numBuckets);

    FacetDefinition.NumericFacetDefinition facetDefinition =
        new FacetDefinition.NumericFacetDefinition("myPath", Optional.empty(), boundaries);
    LuceneBoundaryFacetMetaBucketProducer metaProducer =
        LuceneBoundaryFacetMetaBucketProducer.create(
            facetResult, "myFacet", facetDefinition, facetResult.value.longValue());

    assertBucketCounts(metaProducer, boundaries, facetResult.labelValues, Optional.empty());
  }

  @Test
  public void testDefaultDouble() {
    int numBuckets = 5;
    FacetResult facetResult = getFacetResult(numBuckets);
    List<BsonNumber> boundaries = getNumberBoundaries(BsonDouble::new, numBuckets);

    FacetDefinition.NumericFacetDefinition facetDefinition =
        new FacetDefinition.NumericFacetDefinition("myPath", Optional.of("default"), boundaries);
    LuceneBoundaryFacetMetaBucketProducer metaProducer =
        LuceneBoundaryFacetMetaBucketProducer.create(
            facetResult, "myFacet", facetDefinition, facetResult.value.longValue() + DEFAULT_COUNT);

    assertBucketCounts(
        metaProducer,
        boundaries,
        facetResult.labelValues,
        Optional.of(new DefaultBucketInfo("default", DEFAULT_COUNT)));
  }

  @Test
  public void testNoDefaultDate() {
    int numBuckets = 5;

    FacetResult facetResult = getFacetResult(numBuckets);
    List<BsonDateTime> boundaries = getDateBoundaries(BsonDateTime::new, numBuckets);

    FacetDefinition.DateFacetDefinition facetDefinition =
        new FacetDefinition.DateFacetDefinition("myPath", Optional.empty(), boundaries);
    LuceneBoundaryFacetMetaBucketProducer metaProducer =
        LuceneBoundaryFacetMetaBucketProducer.create(
            facetResult, "myFacet", facetDefinition, facetResult.value.longValue());

    assertBucketCounts(metaProducer, boundaries, facetResult.labelValues, Optional.empty());
  }

  @Test
  public void testDefaultDate() {
    int numBuckets = 5;
    FacetResult facetResult = getFacetResult(numBuckets);
    List<BsonDateTime> boundaries = getDateBoundaries(BsonDateTime::new, numBuckets);

    FacetDefinition.DateFacetDefinition facetDefinition =
        new FacetDefinition.DateFacetDefinition("myPath", Optional.of("default"), boundaries);
    LuceneBoundaryFacetMetaBucketProducer metaProducer =
        LuceneBoundaryFacetMetaBucketProducer.create(
            facetResult, "myFacet", facetDefinition, facetResult.value.longValue() + DEFAULT_COUNT);

    assertBucketCounts(
        metaProducer,
        boundaries,
        facetResult.labelValues,
        Optional.of(new DefaultBucketInfo("default", DEFAULT_COUNT)));
  }

  private static void assertBucketCounts(
      LuceneBoundaryFacetMetaBucketProducer bucketProducer,
      List<? extends BsonValue> boundaries,
      LabelAndValue[] labelAndValues,
      Optional<DefaultBucketInfo> defaultBucket) {
    @Var int i = 0;
    while (!bucketProducer.isExhausted()) {
      Assert.assertFalse(
          "If the default bucket is present, the producer should be "
              + "exhausted when i == labelValues.length",
          i == labelAndValues.length && defaultBucket.isEmpty());
      Assert.assertFalse("The producer produced too many buckets.", i > labelAndValues.length);

      IntermediateFacetBucket bucket = bucketProducer.peek();

      if (i == labelAndValues.length) {
        // This is the default bucket
        Assert.assertEquals(new BsonString(defaultBucket.get().bucketName), bucket.bucket());
        Assert.assertEquals(defaultBucket.get().bucketCount, bucket.count());
      } else {
        Assert.assertEquals(boundaries.get(i), bucket.bucket());
        Assert.assertEquals(labelAndValues[i].value.longValue(), bucket.count());
      }

      Assert.assertEquals(IntermediateFacetBucket.Type.FACET, bucket.type());
      bucketProducer.acceptAndAdvance();
      i++;
    }
  }

  private static List<BsonNumber> getNumberBoundaries(
      Function<Integer, BsonNumber> boundaryProducer, int numBuckets) {
    List<BsonNumber> boundaries = new ArrayList<>();
    for (int i = 0; i < numBuckets; i++) {
      boundaries.add(boundaryProducer.apply(i));
    }

    return boundaries;
  }

  private static List<BsonDateTime> getDateBoundaries(
      Function<Integer, BsonDateTime> boundaryProducer, int numBuckets) {
    List<BsonDateTime> boundaries = new ArrayList<>();
    for (int i = 0; i < numBuckets; i++) {
      boundaries.add(boundaryProducer.apply(i));
    }

    return boundaries;
  }

  private static FacetResult getFacetResult(int numBuckets) {
    @Var int totalCount = 0;
    LabelAndValue[] labelAndValues = new LabelAndValue[numBuckets];
    for (int i = 0; i < numBuckets; i++) {
      labelAndValues[i] = new LabelAndValue(String.valueOf(i), i);
      totalCount += i;
    }

    return new FacetResult(
        "myDim", new String[] {"my", "path"}, totalCount, labelAndValues, numBuckets);
  }
}
