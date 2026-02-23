package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.FacetBucketBuilder;
import com.xgen.testing.mongot.index.FacetInfoBuilder;
import com.xgen.testing.mongot.index.MetaResultsBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.bson.BsonString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {VariablesTest.TestDeserialization.class, VariablesTest.TestSerialization.class})
public class VariablesTest {

  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "variables-deserialization";

    private static final BsonDeserializationTestSuite<Variables> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, Variables::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<Variables> testSpec;

    public TestDeserialization(BsonDeserializationTestSuite.TestSpecWrapper<Variables> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<Variables>> data() {
      return TEST_SUITE.withExamples(simple());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<Variables> simple() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "simple",
          new Variables(
              MetaResultsBuilder.builder()
                  .count(CountResult.lowerBoundCount(1000))
                  .facet(
                      Map.of(
                          "myFacet",
                          FacetInfoBuilder.builder()
                              .buckets(
                                  List.of(
                                      FacetBucketBuilder.builder()
                                          .id(new BsonString("category"))
                                          .count(2L)
                                          .build()))
                              .build()))
                  .build()));
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "variables-serialization";
    private static final BsonSerializationTestSuite<Variables> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<Variables> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<Variables> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<Variables>> data() {
      return Arrays.asList(simple());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<Variables> simple() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple",
          new Variables(
              MetaResultsBuilder.builder()
                  .count(CountResult.lowerBoundCount(2147483648L))
                  .facet(
                      Map.of(
                          "myFacet",
                          FacetInfoBuilder.builder()
                              .buckets(
                                  List.of(
                                      FacetBucketBuilder.builder()
                                          .id(new BsonString("category"))
                                          .count(2147483649L)
                                          .build()))
                              .build()))
                  .build()));
    }
  }
}
