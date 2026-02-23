package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.SearchHighlightBuilder;
import com.xgen.testing.mongot.index.SearchHighlightTextBuilder;
import com.xgen.testing.mongot.index.path.string.StringPathBuilder;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      SearchHighlightTest.TestDeserialization.class,
      SearchHighlightTest.TestSerialization.class
    })
public class SearchHighlightTest {

  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "search-highlight-deserialization";

    private static final BsonDeserializationTestSuite<SearchHighlight> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, SearchHighlight::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<SearchHighlight> testSpec;

    public TestDeserialization(
        BsonDeserializationTestSuite.TestSpecWrapper<SearchHighlight> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<SearchHighlight>> data() {
      return TEST_SUITE.withExamples(simple());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchHighlight> simple() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "simple",
          SearchHighlightBuilder.builder()
              .score(1234.0f)
              .path(StringPathBuilder.fieldPath("testPath"))
              .texts(
                  List.of(
                      SearchHighlightTextBuilder.builder()
                          .value("firstText")
                          .type(SearchHighlightText.Type.TEXT)
                          .build(),
                      SearchHighlightTextBuilder.builder()
                          .value("12Hit")
                          .type(SearchHighlightText.Type.HIT)
                          .build(),
                      SearchHighlightTextBuilder.builder()
                          .value("secondText")
                          .type(SearchHighlightText.Type.TEXT)
                          .build(),
                      SearchHighlightTextBuilder.builder()
                          .value("23Hit")
                          .type(SearchHighlightText.Type.HIT)
                          .build(),
                      SearchHighlightTextBuilder.builder()
                          .value("thirdText")
                          .type(SearchHighlightText.Type.TEXT)
                          .build()))
              .build());
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "search-highlight-serialization";
    private static final BsonSerializationTestSuite<SearchHighlight> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<SearchHighlight> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<SearchHighlight> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<SearchHighlight>> data() {
      return Arrays.asList(simple());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<SearchHighlight> simple() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple",
          SearchHighlightBuilder.builder()
              .score(1234.0f)
              .path(StringPathBuilder.fieldPath("testPath"))
              .texts(
                  List.of(
                      SearchHighlightTextBuilder.builder()
                          .value("firstText")
                          .type(SearchHighlightText.Type.TEXT)
                          .build(),
                      SearchHighlightTextBuilder.builder()
                          .value("12Hit")
                          .type(SearchHighlightText.Type.HIT)
                          .build(),
                      SearchHighlightTextBuilder.builder()
                          .value("secondText")
                          .type(SearchHighlightText.Type.TEXT)
                          .build(),
                      SearchHighlightTextBuilder.builder()
                          .value("23Hit")
                          .type(SearchHighlightText.Type.HIT)
                          .build(),
                      SearchHighlightTextBuilder.builder()
                          .value("thirdText")
                          .type(SearchHighlightText.Type.TEXT)
                          .build()))
              .build());
    }
  }
}
