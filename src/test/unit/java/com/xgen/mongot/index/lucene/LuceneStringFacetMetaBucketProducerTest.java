package com.xgen.mongot.index.lucene;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.index.IntermediateFacetBucket;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.LabelAndValue;
import org.bson.BsonString;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LuceneStringFacetMetaBucketProducerTest {

  private FacetResult facetResult;

  @Before
  public void before() {
    int numBuckets = 25;
    LabelAndValue[] buckets = new LabelAndValue[numBuckets];
    for (int i = 0; i < numBuckets; i++) {
      buckets[i] = new LabelAndValue(String.valueOf(i + 1), i + 1);
    }
    this.facetResult =
        new FacetResult("dim", new String[] {"path"}, numBuckets, buckets, numBuckets);
  }

  @Test
  public void testBuckets() throws Exception {
    LuceneStringFacetMetaBucketProducer producer =
        LuceneStringFacetMetaBucketProducer.create(this.facetResult, "myPath");

    @Var int ordPos = 1;
    while (!producer.isExhausted()) {
      IntermediateFacetBucket bucket = producer.peek();

      Assert.assertEquals(new BsonString(String.valueOf(ordPos)), bucket.bucket());
      Assert.assertEquals(ordPos, bucket.count());

      producer.acceptAndAdvance();
      ordPos++;
    }
  }

  @Test
  public void testDoesntOutputEmptyBuckets() throws Exception {
    LuceneStringFacetMetaBucketProducer producer =
        LuceneStringFacetMetaBucketProducer.create(this.facetResult, "myPath");

    // First bucket should be for "1" because the "0" bucket has a count of 0.
    @Var int ordPos = 1;
    while (!producer.isExhausted()) {
      IntermediateFacetBucket bucket = producer.peek();

      Assert.assertEquals(new BsonString(String.valueOf(ordPos)), bucket.bucket());
      Assert.assertEquals(ordPos, bucket.count());

      producer.acceptAndAdvance();
      ordPos++;
    }
  }
}
