package com.xgen.mongot.index.definition;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.load;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.definition.AutocompleteFieldDefinitionBuilder;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      AutocompleteFieldDefinitionTest.DeserializationTest.class,
      AutocompleteFieldDefinitionTest.SerializationTest.class,
      AutocompleteFieldDefinitionTest.DefinitionTest.class,
    })
public class AutocompleteFieldDefinitionTest {

  @RunWith(Parameterized.class)
  public static class DeserializationTest {

    private static final String SUITE_NAME = "autocomplete-deserialization";
    private static final BsonDeserializationTestSuite<AutocompleteFieldDefinition> TEST_SUITE =
        fromDocument(
            DefinitionTests.RESOURCES_PATH, SUITE_NAME, AutocompleteFieldDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<AutocompleteFieldDefinition>
        testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<AutocompleteFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<
            BsonDeserializationTestSuite.TestSpecWrapper<AutocompleteFieldDefinition>>
        data() {
      return TEST_SUITE.withExamples(
          empty(),
          explicitEdgeGramTokenizationStrategy(),
          explicitNGramTokenizationStrategy(),
          explicitRightEdgeGramTokenizationStrategy(),
          explicitMinGrams(),
          explicitMaxGrams(),
          maxGramsGtMinGrams(),
          maxGramsEqualToMinGrams(),
          explicitFoldDiacritics());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<AutocompleteFieldDefinition> empty() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "empty", AutocompleteFieldDefinitionBuilder.builder().build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AutocompleteFieldDefinition>
        explicitEdgeGramTokenizationStrategy() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit edgeGram tokenizationStrategy",
          AutocompleteFieldDefinitionBuilder.builder()
              .tokenizationStrategy(AutocompleteFieldDefinition.TokenizationStrategy.EDGE_GRAM)
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AutocompleteFieldDefinition>
        explicitNGramTokenizationStrategy() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit nGram tokenizationStrategy",
          AutocompleteFieldDefinitionBuilder.builder()
              .tokenizationStrategy(AutocompleteFieldDefinition.TokenizationStrategy.N_GRAM)
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AutocompleteFieldDefinition>
        explicitRightEdgeGramTokenizationStrategy() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit rightEdgeGram tokenizationStrategy",
          AutocompleteFieldDefinitionBuilder.builder()
              .tokenizationStrategy(
                  AutocompleteFieldDefinition.TokenizationStrategy.RIGHT_EDGE_GRAM)
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AutocompleteFieldDefinition>
        explicitMinGrams() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit minGrams", AutocompleteFieldDefinitionBuilder.builder().minGrams(12).build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AutocompleteFieldDefinition>
        explicitMaxGrams() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit maxGrams", AutocompleteFieldDefinitionBuilder.builder().maxGrams(19).build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AutocompleteFieldDefinition>
        maxGramsGtMinGrams() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "maxGrams gt minGrams",
          AutocompleteFieldDefinitionBuilder.builder().minGrams(12).maxGrams(13).build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AutocompleteFieldDefinition>
        maxGramsEqualToMinGrams() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "maxGrams equal to minGrams",
          AutocompleteFieldDefinitionBuilder.builder().minGrams(13).maxGrams(13).build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<AutocompleteFieldDefinition>
        explicitFoldDiacritics() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit foldDiacritics",
          AutocompleteFieldDefinitionBuilder.builder().foldDiacritics(false).build());
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {

    private static final String SUITE_NAME = "autocomplete-serialization";
    private static final BsonSerializationTestSuite<AutocompleteFieldDefinition> TEST_SUITE =
        load(
            DefinitionTests.RESOURCES_PATH,
            SUITE_NAME,
            AutocompleteFieldDefinition::fieldTypeToBson);

    private final BsonSerializationTestSuite.TestSpec<AutocompleteFieldDefinition> testSpec;

    public SerializationTest(
        BsonSerializationTestSuite.TestSpec<AutocompleteFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<AutocompleteFieldDefinition>>
        data() {
      return Arrays.asList(simple(), doesNotSerializeAnalyzerWhenAbsent());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<AutocompleteFieldDefinition> simple() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple",
          AutocompleteFieldDefinitionBuilder.builder()
              .tokenizationStrategy(AutocompleteFieldDefinition.TokenizationStrategy.EDGE_GRAM)
              .minGrams(12)
              .maxGrams(19)
              .foldDiacritics(true)
              .analyzer("lucene.standard")
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<AutocompleteFieldDefinition>
        doesNotSerializeAnalyzerWhenAbsent() {
      return BsonSerializationTestSuite.TestSpec.create(
          "doesnt serialize empty analyzer",
          AutocompleteFieldDefinitionBuilder.builder()
              .tokenizationStrategy(AutocompleteFieldDefinition.TokenizationStrategy.N_GRAM)
              .minGrams(1)
              .maxGrams(2)
              .foldDiacritics(true)
              .build());
    }
  }

  public static class DefinitionTest {

    @Test
    public void testGetType() {
      AutocompleteFieldDefinition definition = AutocompleteFieldDefinitionBuilder.builder().build();
      Assert.assertEquals(FieldTypeDefinition.Type.AUTOCOMPLETE, definition.getType());
    }

    @Test
    public void testGetMinGrams() {
      AutocompleteFieldDefinition definition =
          AutocompleteFieldDefinitionBuilder.builder().minGrams(2).build();

      Assert.assertEquals(2, definition.getMinGrams());
    }

    @Test
    public void testGetMaxGrams() {
      AutocompleteFieldDefinition definition =
          AutocompleteFieldDefinitionBuilder.builder().maxGrams(25).build();

      Assert.assertEquals(25, definition.getMaxGrams());
    }

    @Test
    public void testIsFoldDiacritics() {
      AutocompleteFieldDefinition definition =
          AutocompleteFieldDefinitionBuilder.builder().foldDiacritics(false).build();

      Assert.assertFalse(definition.isFoldDiacritics());
    }

    @Test
    public void testGetTokenization() {
      AutocompleteFieldDefinition definition =
          AutocompleteFieldDefinitionBuilder.builder()
              .tokenizationStrategy(AutocompleteFieldDefinition.TokenizationStrategy.N_GRAM)
              .build();

      Assert.assertEquals(
          AutocompleteFieldDefinition.TokenizationStrategy.N_GRAM, definition.getTokenization());
    }

    @Test
    public void testGetAnalyzer() {
      AutocompleteFieldDefinition definition =
          AutocompleteFieldDefinitionBuilder.builder().analyzer("myAnalyzer").build();

      Assert.assertEquals("myAnalyzer", definition.getAnalyzer());
    }

    @Test
    public void testGetAnalyzerDefault() {
      AutocompleteFieldDefinition definition = AutocompleteFieldDefinitionBuilder.builder().build();

      Assert.assertEquals("lucene.standard", definition.getAnalyzer());
    }

    @Test
    public void testEquals() {
      TestUtils.assertEqualityGroups(
          () -> AutocompleteFieldDefinitionBuilder.builder().foldDiacritics(false).build(),
          () ->
              AutocompleteFieldDefinitionBuilder.builder()
                  .tokenizationStrategy(AutocompleteFieldDefinition.TokenizationStrategy.EDGE_GRAM)
                  .build(),
          () ->
              AutocompleteFieldDefinitionBuilder.builder()
                  .tokenizationStrategy(AutocompleteFieldDefinition.TokenizationStrategy.N_GRAM)
                  .build(),
          () ->
              AutocompleteFieldDefinitionBuilder.builder()
                  .tokenizationStrategy(
                      AutocompleteFieldDefinition.TokenizationStrategy.RIGHT_EDGE_GRAM)
                  .build(),
          () -> AutocompleteFieldDefinitionBuilder.builder().minGrams(3).build(),
          () -> AutocompleteFieldDefinitionBuilder.builder().minGrams(5).build(),
          () -> AutocompleteFieldDefinitionBuilder.builder().maxGrams(4).build(),
          () -> AutocompleteFieldDefinitionBuilder.builder().maxGrams(9).build(),
          () -> AutocompleteFieldDefinitionBuilder.builder().analyzer("lucene.english").build(),
          () -> AutocompleteFieldDefinitionBuilder.builder().analyzer("lucene.spanish").build());
    }
  }
}
