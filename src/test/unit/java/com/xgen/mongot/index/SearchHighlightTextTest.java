package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.SearchHighlightTextBuilder;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      SearchHighlightTextTest.TestDeserialization.class,
      SearchHighlightTextTest.TestSerialization.class
    })
public class SearchHighlightTextTest {

  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "search-highlight-text-deserialization";

    private static final BsonDeserializationTestSuite<SearchHighlightText> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, SearchHighlightText::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<SearchHighlightText> testSpec;

    public TestDeserialization(
        BsonDeserializationTestSuite.TestSpecWrapper<SearchHighlightText> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<SearchHighlightText>>
        data() {
      return TEST_SUITE.withExamples(text(), hit());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchHighlightText> text() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "text",
          SearchHighlightTextBuilder.builder()
              .value("firstText")
              .type(SearchHighlightText.Type.TEXT)
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchHighlightText> hit() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "hit",
          SearchHighlightTextBuilder.builder()
              .value("12Hit")
              .type(SearchHighlightText.Type.HIT)
              .build());
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "search-highlight-text-serialization";
    private static final BsonSerializationTestSuite<SearchHighlightText> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<SearchHighlightText> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<SearchHighlightText> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<SearchHighlightText>> data() {
      return Arrays.asList(text(), hit());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<SearchHighlightText> text() {
      return BsonSerializationTestSuite.TestSpec.create(
          "text",
          SearchHighlightTextBuilder.builder()
              .value("firstText")
              .type(SearchHighlightText.Type.TEXT)
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<SearchHighlightText> hit() {
      return BsonSerializationTestSuite.TestSpec.create(
          "hit",
          SearchHighlightTextBuilder.builder()
              .value("12Hit")
              .type(SearchHighlightText.Type.HIT)
              .build());
    }
  }
}
