package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.VectorSearchResultBuilder;
import java.util.Arrays;
import org.bson.BsonInt32;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      VectorSearchResultTest.TestSerialization.class,
      VectorSearchResultTest.TestDeserialization.class
    })
public class VectorSearchResultTest {

  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "vector-search-result-deserialization";

    private static final BsonDeserializationTestSuite<VectorSearchResult> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, VectorSearchResult::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<VectorSearchResult> testSpec;

    public TestDeserialization(
        BsonDeserializationTestSuite.TestSpecWrapper<VectorSearchResult> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<VectorSearchResult>>
        data() {
      return TEST_SUITE.withExamples(simple());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<VectorSearchResult> simple() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "simple", VectorSearchResultBuilder.builder().id(new BsonInt32(0)).score(0.0f).build());
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "vector-search-result-serialization";
    private static final BsonSerializationTestSuite<VectorSearchResult> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<VectorSearchResult> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<VectorSearchResult> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<VectorSearchResult>> data() {
      return Arrays.asList(simple());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<VectorSearchResult> simple() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple", VectorSearchResultBuilder.builder().id(new BsonInt32(0)).score(0.0f).build());
    }
  }
}
