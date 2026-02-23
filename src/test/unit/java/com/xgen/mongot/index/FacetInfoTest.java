package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.FacetBucketBuilder;
import com.xgen.testing.mongot.index.FacetInfoBuilder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bson.BsonString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {FacetInfoTest.TestDeserialization.class, FacetInfoTest.TestSerialization.class})
public class FacetInfoTest {

  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "facet-info-deserialization";

    private static final BsonDeserializationTestSuite<FacetInfo> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, FacetInfo::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<FacetInfo> testSpec;

    public TestDeserialization(BsonDeserializationTestSuite.TestSpecWrapper<FacetInfo> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<FacetInfo>> data() {
      return TEST_SUITE.withExamples(simple(), multipleBuckets(), emptyBuckets());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<FacetInfo> simple() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "simple",
          FacetInfoBuilder.builder()
              .buckets(
                  List.of(
                      FacetBucketBuilder.builder()
                          .id(new BsonString("category"))
                          .count(2L)
                          .build()))
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FacetInfo> multipleBuckets() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "multipleBuckets",
          FacetInfoBuilder.builder()
              .buckets(
                  List.of(
                      FacetBucketBuilder.builder()
                          .id(new BsonString("category1"))
                          .count(2L)
                          .build(),
                      FacetBucketBuilder.builder()
                          .id(new BsonString("category2"))
                          .count(2L)
                          .build()))
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FacetInfo> emptyBuckets() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "emptyBuckets", FacetInfoBuilder.builder().buckets(Collections.emptyList()).build());
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "facet-info-serialization";
    private static final BsonSerializationTestSuite<FacetInfo> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<FacetInfo> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<FacetInfo> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<FacetInfo>> data() {
      return Arrays.asList(simple(), multipleBuckets(), emptyBuckets());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<FacetInfo> simple() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple",
          FacetInfoBuilder.builder()
              .buckets(
                  List.of(
                      FacetBucketBuilder.builder()
                          .id(new BsonString("category"))
                          .count(2147483648L)
                          .build()))
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FacetInfo> multipleBuckets() {
      return BsonSerializationTestSuite.TestSpec.create(
          "multipleBuckets",
          FacetInfoBuilder.builder()
              .buckets(
                  List.of(
                      FacetBucketBuilder.builder()
                          .id(new BsonString("category1"))
                          .count(2147483648L)
                          .build(),
                      FacetBucketBuilder.builder()
                          .id(new BsonString("category2"))
                          .count(2147483648L)
                          .build()))
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FacetInfo> emptyBuckets() {
      return BsonSerializationTestSuite.TestSpec.create(
          "emptyBuckets", FacetInfoBuilder.builder().buckets(Collections.emptyList()).build());
    }
  }
}
