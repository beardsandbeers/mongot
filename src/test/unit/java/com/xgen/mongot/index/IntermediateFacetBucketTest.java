package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.IntermediateFacetBucketBuilder;
import java.util.Arrays;
import org.bson.BsonDateTime;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      IntermediateFacetBucketTest.TestDeserialization.class,
      IntermediateFacetBucketTest.TestSerialization.class
    })
public class IntermediateFacetBucketTest {

  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "intermediate-facet-bucket-deserialization";

    private static final BsonDeserializationTestSuite<IntermediateFacetBucket> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, IntermediateFacetBucket::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<IntermediateFacetBucket> testSpec;

    public TestDeserialization(
        BsonDeserializationTestSuite.TestSpecWrapper<IntermediateFacetBucket> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<IntermediateFacetBucket>>
        data() {
      return TEST_SUITE.withExamples(string(), number(), numberLong(), numberDouble(), date());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<IntermediateFacetBucket> string() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "string",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonString("myBucket"))
              .count(1L)
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<IntermediateFacetBucket> number() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "number",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonInt32(0))
              .count(1L)
              .tag("myFacet")
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<IntermediateFacetBucket> numberLong() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "numberLong",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonInt64(0))
              .count(1L)
              .tag("myFacet")
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<IntermediateFacetBucket> numberDouble() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "numberDouble",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonDouble(0.0))
              .count(1L)
              .tag("myFacet")
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<IntermediateFacetBucket> date() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "date",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonDateTime(1585919593000L))
              .count(1L)
              .tag("myFacet")
              .build());
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "intermediate-facet-bucket-serialization";
    private static final BsonSerializationTestSuite<IntermediateFacetBucket> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<IntermediateFacetBucket> testSpec;

    public TestSerialization(
        BsonSerializationTestSuite.TestSpec<IntermediateFacetBucket> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<IntermediateFacetBucket>> data() {
      return Arrays.asList(string(), number(), numberLong(), numberDouble(), date());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<IntermediateFacetBucket> string() {
      return BsonSerializationTestSuite.TestSpec.create(
          "string",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonString("myBucket"))
              .count(1L)
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<IntermediateFacetBucket> number() {
      return BsonSerializationTestSuite.TestSpec.create(
          "number",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonInt32(0))
              .count(1L)
              .tag("myFacet")
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<IntermediateFacetBucket> numberLong() {
      return BsonSerializationTestSuite.TestSpec.create(
          "numberLong",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonInt64(0))
              .count(1L)
              .tag("myFacet")
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<IntermediateFacetBucket> numberDouble() {
      return BsonSerializationTestSuite.TestSpec.create(
          "numberDouble",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonDouble(0.0))
              .count(1L)
              .tag("myFacet")
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<IntermediateFacetBucket> date() {
      return BsonSerializationTestSuite.TestSpec.create(
          "date",
          IntermediateFacetBucketBuilder.builder()
              .type(IntermediateFacetBucket.Type.FACET)
              .tag("myFacet")
              .bucket(new BsonDateTime(1585919593000L))
              .count(1L)
              .tag("myFacet")
              .build());
    }
  }
}
