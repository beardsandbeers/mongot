package com.xgen.mongot.index.definition;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.mongot.index.analyzer.definition.OverriddenBaseAnalyzerDefinition;
import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.index.analyzer.custom.CustomCharFilterDefinitionBuilder;
import com.xgen.testing.mongot.index.analyzer.custom.TokenFilterDefinitionBuilder;
import com.xgen.testing.mongot.index.analyzer.custom.TokenizerDefinitionBuilder;
import com.xgen.testing.mongot.index.analyzer.definition.CustomAnalyzerDefinitionBuilder;
import com.xgen.testing.mongot.index.analyzer.definition.OverriddenBaseAnalyzerDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.AnalyzerBoundSearchIndexDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.SearchIndexDefinitionBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bson.types.ObjectId;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      AnalyzerBoundSearchIndexDefinitionTest.DeserializationTest.class,
      AnalyzerBoundSearchIndexDefinitionTest.SerializationTest.class,
      AnalyzerBoundSearchIndexDefinitionTest.DefinitionTest.class,
    })
public class AnalyzerBoundSearchIndexDefinitionTest {

  @RunWith(Parameterized.class)
  public static class DeserializationTest {

    private static final String SUITE_NAME = "analyzer-bound-index-deserialization";
    private static final BsonDeserializationTestSuite<AnalyzerBoundSearchIndexDefinition>
        TEST_SUITE =
            fromDocument(
                DefinitionTests.RESOURCES_PATH,
                SUITE_NAME,
                AnalyzerBoundSearchIndexDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<AnalyzerBoundSearchIndexDefinition>
        testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<AnalyzerBoundSearchIndexDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<
            BsonDeserializationTestSuite.TestSpecWrapper<AnalyzerBoundSearchIndexDefinition>>
        data() {
      return TEST_SUITE.withExamples(simple(), withAnalyzer(), superfluousCustomAnalyzer());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<AnalyzerBoundSearchIndexDefinition>
        simple() {
      return BsonDeserializationTestSuite.TestSpec.valid("simple", simpleDefinition());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AnalyzerBoundSearchIndexDefinition>
        withAnalyzer() {
      return BsonDeserializationTestSuite.TestSpec.valid("with analyzer", withAnalyzerDefinition());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AnalyzerBoundSearchIndexDefinition>
        superfluousCustomAnalyzer() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "superfluous custom analyzer", superfluousCustomAnalyzerDefinition());
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {

    private static final String SUITE_NAME = "analyzer-bound-index-serialization";
    private static final BsonSerializationTestSuite<AnalyzerBoundSearchIndexDefinition> TEST_SUITE =
        fromEncodable(DefinitionTests.RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<AnalyzerBoundSearchIndexDefinition> testSpec;

    public SerializationTest(
        BsonSerializationTestSuite.TestSpec<AnalyzerBoundSearchIndexDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<AnalyzerBoundSearchIndexDefinition>>
        data() {
      return Arrays.asList(simple(), withAnalyzer(), superfluousCustomAnalyzer());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<AnalyzerBoundSearchIndexDefinition>
        simple() {
      return BsonSerializationTestSuite.TestSpec.create("simple", simpleDefinition());
    }

    private static BsonSerializationTestSuite.TestSpec<AnalyzerBoundSearchIndexDefinition>
        withAnalyzer() {
      return BsonSerializationTestSuite.TestSpec.create("with analyzer", withAnalyzerDefinition());
    }

    private static BsonSerializationTestSuite.TestSpec<AnalyzerBoundSearchIndexDefinition>
        superfluousCustomAnalyzer() {
      return BsonSerializationTestSuite.TestSpec.create(
          "superfluous custom analyzer", superfluousCustomAnalyzerDefinition());
    }
  }

  private static AnalyzerBoundSearchIndexDefinition withAnalyzerDefinition() {
    return AnalyzerBoundSearchIndexDefinitionBuilder.builder()
        .index(
            SearchIndexDefinitionBuilder.builder()
                .indexId(new ObjectId("507f191e810c19729de860ea"))
                .name("index")
                .database("database")
                .lastObservedCollectionName("collection")
                .collectionUuid(UUID.fromString("eb6c40ca-f25e-47e8-b48c-02a05b64a5aa"))
                .dynamicMapping()
                .analyzerName("my-analyzer")
                .build())
        .analyzer(
            OverriddenBaseAnalyzerDefinitionBuilder.builder()
                .baseAnalyzerName("lucene.standard")
                .name("my-analyzer")
                .build())
        .build();
  }

  private static AnalyzerBoundSearchIndexDefinition simpleDefinition() {
    return AnalyzerBoundSearchIndexDefinitionBuilder.builder()
        .index(
            SearchIndexDefinitionBuilder.builder()
                .indexId(new ObjectId("507f191e810c19729de860ea"))
                .name("index")
                .database("database")
                .lastObservedCollectionName("collection")
                .collectionUuid(UUID.fromString("eb6c40ca-f25e-47e8-b48c-02a05b64a5aa"))
                .dynamicMapping()
                .build())
        .build();
  }

  private static AnalyzerBoundSearchIndexDefinition superfluousCustomAnalyzerDefinition() {
    return AnalyzerBoundSearchIndexDefinitionBuilder.builder()
        .index(
            SearchIndexDefinitionBuilder.builder()
                .indexId(new ObjectId("507f191e810c19729de860ea"))
                .name("index")
                .database("database")
                .lastObservedCollectionName("collection")
                .collectionUuid(UUID.fromString("eb6c40ca-f25e-47e8-b48c-02a05b64a5aa"))
                .dynamicMapping()
                .analyzers(
                    List.of(
                        CustomAnalyzerDefinitionBuilder.builder(
                                "unreferencedCustomAnalyzerName",
                                TokenizerDefinitionBuilder.StandardTokenizer.builder().build())
                            .tokenFilter(
                                TokenFilterDefinitionBuilder.LengthTokenFilter.builder()
                                    .min(2)
                                    .max(12)
                                    .build())
                            .charFilter(
                                CustomCharFilterDefinitionBuilder.HtmlStripCharFilter.builder()
                                    .build())
                            .build()))
                .build())
        .build();
  }

  public static class DefinitionTest {
    @Test
    public void testDefaultRequiresNoAnalyzers() {
      var index = SearchIndexDefinitionBuilder.builder().defaultMetadata().dynamicMapping().build();
      assertValid(index);
    }

    @Test
    public void testExtraAnalyzersIsInvalid() {
      var index =
          SearchIndexDefinitionBuilder.builder()
              .defaultMetadata()
              .dynamicMapping()
              .searchAnalyzerName("missing")
              .build();
      assertInvalid(index);
    }

    @Test
    public void testDifferentStockAnalyzersDoNotNeedCustomAnalyzerDefinitions() {
      var index =
          SearchIndexDefinitionBuilder.builder()
              .defaultMetadata()
              .dynamicMapping()
              .analyzerName("lucene.simple")
              .searchAnalyzerName("lucene.english")
              .build();
      assertValid(index);
    }

    @Test
    public void testValidWithOverriddenAnalyzers() {
      var index =
          SearchIndexDefinitionBuilder.builder()
              .defaultMetadata()
              .dynamicMapping()
              .analyzerName("myAnalyzer")
              .searchAnalyzerName("mySearchAnalyzer")
              .build();
      var definition1 =
          OverriddenBaseAnalyzerDefinitionBuilder.builder()
              .name("myAnalyzer")
              .baseAnalyzerName("lucene.standard")
              .build();
      var definition2 =
          OverriddenBaseAnalyzerDefinitionBuilder.builder()
              .name("mySearchAnalyzer")
              .baseAnalyzerName("lucene.standard")
              .build();
      assertValid(index, definition1, definition2);
    }

    @Test
    public void testUnreferencedAnalyzerInvalid() {
      var index = SearchIndexDefinitionBuilder.builder().defaultMetadata().dynamicMapping().build();
      var notReferenced =
          OverriddenBaseAnalyzerDefinitionBuilder.builder()
              .name("myAnalyzer")
              .baseAnalyzerName("lucene.standard")
              .build();
      assertInvalid(index, notReferenced);
    }

    @Test
    public void testUnreferencedCustomAnalyzerValid() {
      var builder = SearchIndexDefinitionBuilder.builder().defaultMetadata().dynamicMapping();
      var notReferenced =
          CustomAnalyzerDefinitionBuilder.builder(
                  "customAz", TokenizerDefinitionBuilder.StandardTokenizer.builder().build())
              .build();
      var index = builder.analyzers(List.of(notReferenced)).build();

      assertValid(index);
    }

    private void assertValid(
        SearchIndexDefinition index, OverriddenBaseAnalyzerDefinition... analyzers) {
      AnalyzerBoundSearchIndexDefinition.withRelevantOverriddenAnalyzers(index, List.of(analyzers));
    }

    private void assertInvalid(
        SearchIndexDefinition index, OverriddenBaseAnalyzerDefinition... analyzers) {
      Assert.assertThrows(
          IllegalArgumentException.class,
          () -> AnalyzerBoundSearchIndexDefinition.create(index, List.of(analyzers)));
    }
  }
}
