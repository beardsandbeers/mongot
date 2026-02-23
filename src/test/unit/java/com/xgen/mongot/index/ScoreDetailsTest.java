package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.ScoreDetailsBuilder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {ScoreDetailsTest.TestSerialization.class, ScoreDetailsTest.TestDeserialization.class})
public class ScoreDetailsTest {
  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "score-details-deserialization";

    private static final BsonDeserializationTestSuite<ScoreDetails> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, ScoreDetails::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<ScoreDetails> testSpec;

    public TestDeserialization(
        BsonDeserializationTestSuite.TestSpecWrapper<ScoreDetails> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<ScoreDetails>> data() {
      return TEST_SUITE.withExamples(simpleNoDetails(), nearQuery());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<ScoreDetails> simpleNoDetails() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "simple no details",
          ScoreDetailsBuilder.builder()
              .value(0.0f)
              .description("description")
              .details(Collections.emptyList())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<ScoreDetails> nearQuery() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "near query",
          ScoreDetailsBuilder.builder()
              .value(5.0f)
              .description(
                  "Distance score, computed as weight * pivotDistance / (pivotDistance + abs(value"
                      + " - origin)) from:")
              .details(
                  List.of(
                      ScoreDetailsBuilder.builder()
                          .value(1.0f)
                          .description("weight")
                          .details(Collections.emptyList())
                          .build(),
                      ScoreDetailsBuilder.builder()
                          .value(2.0f)
                          .description("pivotDistance")
                          .details(Collections.emptyList())
                          .build(),
                      ScoreDetailsBuilder.builder()
                          .value(3.0f)
                          .description("origin")
                          .details(Collections.emptyList())
                          .build(),
                      ScoreDetailsBuilder.builder()
                          .value(4.0f)
                          .description("current value")
                          .details(Collections.emptyList())
                          .build()))
              .build());
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "score-details-serialization";
    private static final BsonSerializationTestSuite<ScoreDetails> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<ScoreDetails> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<ScoreDetails> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<ScoreDetails>> data() {
      return Arrays.asList(simpleNoDetails(), nearQuery());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<ScoreDetails> simpleNoDetails() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple no details",
          ScoreDetailsBuilder.builder()
              .value(0.0f)
              .description("description")
              .details(Collections.emptyList())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<ScoreDetails> nearQuery() {
      return BsonSerializationTestSuite.TestSpec.create(
          "near query",
          ScoreDetailsBuilder.builder()
              .value(5.0f)
              .description(
                  "Distance score, computed as weight * pivotDistance / (pivotDistance + abs(value"
                      + " - origin)) from:")
              .details(
                  List.of(
                      ScoreDetailsBuilder.builder()
                          .value(1.0f)
                          .description("weight")
                          .details(Collections.emptyList())
                          .build(),
                      ScoreDetailsBuilder.builder()
                          .value(2.0f)
                          .description("pivotDistance")
                          .details(Collections.emptyList())
                          .build(),
                      ScoreDetailsBuilder.builder()
                          .value(3.0f)
                          .description("origin")
                          .details(Collections.emptyList())
                          .build(),
                      ScoreDetailsBuilder.builder()
                          .value(4.0f)
                          .description("current value")
                          .details(Collections.emptyList())
                          .build()))
              .build());
    }
  }
}
