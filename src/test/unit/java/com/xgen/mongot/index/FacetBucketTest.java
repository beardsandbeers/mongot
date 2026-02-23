package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.FacetBucketBuilder;
import java.util.Arrays;
import org.bson.BsonDateTime;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {FacetBucketTest.TestDeserialization.class, FacetBucketTest.TestSerialization.class})
public class FacetBucketTest {

  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "facet-bucket-deserialization";

    private static final BsonDeserializationTestSuite<FacetBucket> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, FacetBucket::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<FacetBucket> testSpec;

    public TestDeserialization(BsonDeserializationTestSuite.TestSpecWrapper<FacetBucket> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<FacetBucket>> data() {
      return TEST_SUITE.withExamples(simple(), numericId(), dateId());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<FacetBucket> simple() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "simple", FacetBucketBuilder.builder().id(new BsonString("category")).count(2L).build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FacetBucket> numericId() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "numericId", FacetBucketBuilder.builder().id(new BsonInt32(1)).count(2L).build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FacetBucket> dateId() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "dateId",
          FacetBucketBuilder.builder().id(new BsonDateTime(1585919593000L)).count(2L).build());
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "facet-bucket-serialization";
    private static final BsonSerializationTestSuite<FacetBucket> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<FacetBucket> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<FacetBucket> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<FacetBucket>> data() {
      return Arrays.asList(simple(), numericId(), dateId());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<FacetBucket> simple() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple",
          FacetBucketBuilder.builder().id(new BsonString("category")).count(2147483648L).build());
    }

    private static BsonSerializationTestSuite.TestSpec<FacetBucket> numericId() {
      return BsonSerializationTestSuite.TestSpec.create(
          "numericId",
          FacetBucketBuilder.builder().id(new BsonInt32(1)).count(2147483648L).build());
    }

    private static BsonSerializationTestSuite.TestSpec<FacetBucket> dateId() {
      return BsonSerializationTestSuite.TestSpec.create(
          "dateId",
          FacetBucketBuilder.builder()
              .id(new BsonDateTime(1585919593000L))
              .count(2147483648L)
              .build());
    }
  }
}
