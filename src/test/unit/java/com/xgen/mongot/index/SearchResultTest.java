package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.mongot.index.query.sort.SequenceToken;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.ScoreDetailsBuilder;
import com.xgen.testing.mongot.index.SearchHighlightBuilder;
import com.xgen.testing.mongot.index.SearchHighlightTextBuilder;
import com.xgen.testing.mongot.index.SearchResultBuilder;
import com.xgen.testing.mongot.index.SearchSortValuesBuilder;
import com.xgen.testing.mongot.index.path.string.StringPathBuilder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.lucene.search.ScoreDoc;
import org.bson.BsonBinary;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.UuidRepresentation;
import org.bson.types.ObjectId;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      SearchResultTest.TestSerialization.class,
      SearchResultTest.TestDeserialization.class,
      SearchResultTest.TestLogic.class
    })
public class SearchResultTest {

  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "search-result-deserialization";

    private static final BsonDeserializationTestSuite<SearchResult> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, SearchResult::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<SearchResult> testSpec;

    public TestDeserialization(
        BsonDeserializationTestSuite.TestSpecWrapper<SearchResult> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<SearchResult>> data()
        throws BsonParseException {
      return TEST_SUITE.withExamples(
          simple(),
          withHighlights(),
          withStoredSource(),
          withScoreDetails(),
          withSearchSortValues(),
          withSequenceToken(),
          withStoredSourceAndRootId());
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchResult> withSequenceToken()
        throws BsonParseException {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "with sequence token",
          SearchResultBuilder.builder()
              .id(new BsonInt32(0))
              .score(0f)
              .sequenceToken(SequenceToken.of("CAEVAAAAQCIGOgR0ZXN0"))
              .build());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchResult> simple() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "simple", SearchResultBuilder.builder().id(new BsonInt32(0)).score(0.0f).build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchResult> withHighlights() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "with highlights",
          SearchResultBuilder.builder()
              .id(new BsonInt32(0))
              .score(0.0f)
              .highlights(
                  List.of(
                      SearchHighlightBuilder.builder()
                          .score(1234.0f)
                          .path(StringPathBuilder.fieldPath("testPath"))
                          .texts(
                              List.of(
                                  SearchHighlightTextBuilder.builder()
                                      .value("firstText")
                                      .type(SearchHighlightText.Type.TEXT)
                                      .build()))
                          .build()))
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchResult> withStoredSource() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "with stored source",
          SearchResultBuilder.builder()
              .score(0.0f)
              .storedSource(
                  new BsonDocument()
                      .append("_id", new BsonInt32(0))
                      .append("myNumber", new BsonDouble(1.2345))
                      .append("myString", new BsonString("pizza"))
                      .append("myDocument", new BsonDocument("key", new BsonString("pasta"))))
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchResult> withScoreDetails() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "with score details",
          SearchResultBuilder.builder()
              .id(new BsonInt32(0))
              .score(0.0f)
              .scoreDetails(
                  ScoreDetailsBuilder.builder()
                      .value(0.0f)
                      .description("description")
                      .details(
                          List.of(
                              ScoreDetailsBuilder.builder()
                                  .value(1.0f)
                                  .description("weight")
                                  .details(Collections.emptyList())
                                  .build()))
                      .build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchResult> withSearchSortValues() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "with searchSortValues",
          SearchResultBuilder.builder()
              .id(new BsonInt32(0))
              .score(0.0f)
              .searchSortValues(
                  SearchSortValuesBuilder.builder()
                      .value(new BsonDouble(6.0))
                      .value(new BsonString("foo"))
                      .value(
                          new BsonBinary(
                              UUID.fromString("00000000-1111-2222-3333-444444444444"),
                              UuidRepresentation.STANDARD))
                      .value(new BsonObjectId(new ObjectId("507f1f77bcf86cd799439011")))
                      .value(BsonBoolean.FALSE)
                      .build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<SearchResult>
        withStoredSourceAndRootId() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "with stored source and root id",
          SearchResultBuilder.builder()
              .score(0.0f)
              .storedSource(
                  new BsonDocument()
                      .append("_id", new BsonInt32(0))
                      .append("myNumber", new BsonDouble(1.2345))
                      .append("myString", new BsonString("pizza"))
                      .append("myDocument", new BsonDocument("key", new BsonString("pasta"))))
              .searchRootDocumentId(new BsonInt32(0))
              .build());
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "search-result-serialization";
    private static final BsonSerializationTestSuite<SearchResult> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<SearchResult> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<SearchResult> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<SearchResult>> data()
        throws BsonParseException {
      return Arrays.asList(
          simple(),
          withHighlights(),
          withStoredSource(),
          withScoreDetails(),
          withSearchSortValues(),
          withSequenceToken(),
          withStoredSourceAndRootId());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<SearchResult> simple() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple", SearchResultBuilder.builder().id(new BsonInt32(0)).score(0.0f).build());
    }

    private static BsonSerializationTestSuite.TestSpec<SearchResult> withHighlights() {
      return BsonSerializationTestSuite.TestSpec.create(
          "with highlights",
          SearchResultBuilder.builder()
              .id(new BsonInt32(0))
              .score(0.0f)
              .highlights(
                  List.of(
                      SearchHighlightBuilder.builder()
                          .score(1234.0f)
                          .path(StringPathBuilder.fieldPath("testPath"))
                          .texts(
                              List.of(
                                  SearchHighlightTextBuilder.builder()
                                      .value("firstText")
                                      .type(SearchHighlightText.Type.TEXT)
                                      .build()))
                          .build()))
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<SearchResult> withStoredSource() {
      return BsonSerializationTestSuite.TestSpec.create(
          "with stored source",
          SearchResultBuilder.builder()
              .score(0.0f)
              .storedSource(
                  new BsonDocument()
                      .append("_id", new BsonInt32(0))
                      .append("myNumber", new BsonDouble(1.2345))
                      .append("myString", new BsonString("pizza"))
                      .append("myDocument", new BsonDocument("key", new BsonString("pasta"))))
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<SearchResult> withScoreDetails() {
      return BsonSerializationTestSuite.TestSpec.create(
          "with score details",
          SearchResultBuilder.builder()
              .id(new BsonInt32(0))
              .score(0.0f)
              .scoreDetails(
                  ScoreDetailsBuilder.builder()
                      .value(0.0f)
                      .description("description")
                      .details(
                          List.of(
                              ScoreDetailsBuilder.builder()
                                  .value(1.0f)
                                  .description("weight")
                                  .details(Collections.emptyList())
                                  .build()))
                      .build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<SearchResult> withSearchSortValues() {
      return BsonSerializationTestSuite.TestSpec.create(
          "with searchSortValues",
          SearchResultBuilder.builder()
              .id(new BsonInt32(0))
              .score(0.0f)
              .searchSortValues(
                  SearchSortValuesBuilder.builder()
                      .value(new BsonDouble(6.0))
                      .value(new BsonString("foo"))
                      .value(
                          new BsonBinary(
                              UUID.fromString("00000000-1111-2222-3333-444444444444"),
                              UuidRepresentation.STANDARD))
                      .value(new BsonObjectId(new ObjectId("507f1f77bcf86cd799439011")))
                      .value(BsonBoolean.FALSE)
                      .build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<SearchResult> withSequenceToken() {
      return BsonSerializationTestSuite.TestSpec.create(
          "with sequence token",
          SearchResultBuilder.builder()
              .id(new BsonInt32(0))
              .score(0.0f)
              .sequenceToken(SequenceToken.of(new BsonString("test"), new ScoreDoc(1, 2f)))
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<SearchResult> withStoredSourceAndRootId() {
      return BsonSerializationTestSuite.TestSpec.create(
          "with stored source and root id",
          SearchResultBuilder.builder()
              .score(0.0f)
              .storedSource(
                  new BsonDocument()
                      .append("_id", new BsonInt32(0))
                      .append("myNumber", new BsonDouble(1.2345))
                      .append("myString", new BsonString("pizza"))
                      .append("myDocument", new BsonDocument("key", new BsonString("pasta"))))
              .searchRootDocumentId(new BsonInt32(0))
              .build());
    }
  }

  public static class TestLogic {

    @Test
    public void testFailsToInstantiateWithoutIdAndStoredSource() {
      TestUtils.assertThrows(
          "Either id or storedSource should be present",
          IllegalArgumentException.class,
          () ->
              new SearchResult(
                  Optional.empty(),
                  0.123f,
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty()));
    }
  }
}
