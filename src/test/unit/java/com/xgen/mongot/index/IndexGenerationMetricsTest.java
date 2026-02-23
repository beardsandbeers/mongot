package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.IndexGenerationMetricsBuilder;
import com.xgen.testing.mongot.index.IndexMetricsBuilder;
import com.xgen.testing.mongot.index.version.GenerationIdBuilder;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      IndexGenerationMetricsTest.TestDeserialization.class,
      IndexGenerationMetricsTest.TestSerialization.class,
    })
public class IndexGenerationMetricsTest {
  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "index-generation-metrics-deserialization";
    private static final BsonDeserializationTestSuite<IndexGenerationMetrics> TEST_SUITE =
        fromDocument("src/test/unit/resources/index", SUITE_NAME, IndexGenerationMetrics::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<IndexGenerationMetrics> testSpec;

    public TestDeserialization(
        BsonDeserializationTestSuite.TestSpecWrapper<IndexGenerationMetrics> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<IndexGenerationMetrics>>
        data() {
      return TEST_SUITE.withExamples(simple());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<IndexGenerationMetrics> simple() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "simple",
          IndexGenerationMetricsBuilder.builder()
              .generationId(GenerationIdBuilder.create(new ObjectId("507f191e810c19729de860ea")))
              .indexMetrics(IndexMetricsBuilder.sample())
              .build());
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "index-generation-metrics-serialization";
    private static final BsonSerializationTestSuite<IndexGenerationMetrics> TEST_SUITE =
        fromEncodable("src/test/unit/resources/index", SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<IndexGenerationMetrics> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<IndexGenerationMetrics> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<IndexGenerationMetrics>> data() {
      return List.of(simple());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<IndexGenerationMetrics> simple() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple",
          IndexGenerationMetricsBuilder.builder()
              .generationId(
                  GenerationIdBuilder.create(new ObjectId("507f191e810c19729de860ea"), 0, 5, 0))
              .indexMetrics(IndexMetricsBuilder.sample())
              .build());
    }
  }
}
