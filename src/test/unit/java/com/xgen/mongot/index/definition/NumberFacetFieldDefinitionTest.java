package com.xgen.mongot.index.definition;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.load;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.definition.NumericFieldDefinitionBuilder;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      NumberFacetFieldDefinitionTest.DeserializationTest.class,
      NumberFacetFieldDefinitionTest.SerializationTest.class,
      NumberFacetFieldDefinitionTest.DefinitionTest.class,
    })
public class NumberFacetFieldDefinitionTest {

  @RunWith(Parameterized.class)
  public static class DeserializationTest {

    private static final String SUITE_NAME = "number-facet-deserialization";
    private static final BsonDeserializationTestSuite<NumberFacetFieldDefinition> TEST_SUITE =
        fromDocument(
            DefinitionTests.RESOURCES_PATH, SUITE_NAME, NumberFacetFieldDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<NumberFacetFieldDefinition> testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<NumberFacetFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<NumberFacetFieldDefinition>>
        data() {
      return TEST_SUITE.withExamples(
          empty(),
          explicitDoubleRepresentation(),
          explicitInt64Representation(),
          explicitIndexDoubles(),
          explicitIndexIntegers());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<NumberFacetFieldDefinition> empty() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "empty", NumericFieldDefinitionBuilder.builder().buildNumberFacetField());
    }

    private static BsonDeserializationTestSuite.ValidSpec<NumberFacetFieldDefinition>
        explicitDoubleRepresentation() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit double representation",
          NumericFieldDefinitionBuilder.builder()
              .representation(NumericFieldOptions.Representation.DOUBLE)
              .buildNumberFacetField());
    }

    private static BsonDeserializationTestSuite.ValidSpec<NumberFacetFieldDefinition>
        explicitInt64Representation() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit int64 representation",
          NumericFieldDefinitionBuilder.builder()
              .representation(NumericFieldOptions.Representation.INT64)
              .buildNumberFacetField());
    }

    private static BsonDeserializationTestSuite.ValidSpec<NumberFacetFieldDefinition>
        explicitIndexDoubles() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit indexDoubles",
          NumericFieldDefinitionBuilder.builder().indexDoubles(false).buildNumberFacetField());
    }

    private static BsonDeserializationTestSuite.ValidSpec<NumberFacetFieldDefinition>
        explicitIndexIntegers() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit indexIntegers",
          NumericFieldDefinitionBuilder.builder().indexIntegers(false).buildNumberFacetField());
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {

    private static final String SUITE_NAME = "number-facet-serialization";
    private static final BsonSerializationTestSuite<NumberFacetFieldDefinition> TEST_SUITE =
        load(
            DefinitionTests.RESOURCES_PATH,
            SUITE_NAME,
            NumberFacetFieldDefinition::fieldTypeToBson);

    private final BsonSerializationTestSuite.TestSpec<NumberFacetFieldDefinition> testSpec;

    public SerializationTest(
        BsonSerializationTestSuite.TestSpec<NumberFacetFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<NumberFacetFieldDefinition>> data() {
      return Arrays.asList(doubleRepresentation(), int64Representation());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<NumberFacetFieldDefinition>
        doubleRepresentation() {
      return BsonSerializationTestSuite.TestSpec.create(
          "double representation",
          NumericFieldDefinitionBuilder.builder()
              .representation(NumericFieldOptions.Representation.DOUBLE)
              .buildNumberFacetField());
    }

    private static BsonSerializationTestSuite.TestSpec<NumberFacetFieldDefinition>
        int64Representation() {
      return BsonSerializationTestSuite.TestSpec.create(
          "int64 representation",
          NumericFieldDefinitionBuilder.builder()
              .representation(NumericFieldOptions.Representation.INT64)
              .buildNumberFacetField());
    }
  }

  public static class DefinitionTest {

    @Test
    public void testGetType() {
      NumberFacetFieldDefinition definition =
          NumericFieldDefinitionBuilder.builder().buildNumberFacetField();
      Assert.assertEquals(FieldTypeDefinition.Type.NUMBER_FACET, definition.getType());
    }

    @Test
    public void testEquals() {
      TestUtils.assertEqualityGroups(
          () ->
              NumericFieldDefinitionBuilder.builder()
                  .representation(NumericFieldOptions.Representation.INT64)
                  .buildNumberFacetField(),
          () ->
              NumericFieldDefinitionBuilder.builder()
                  .representation(NumericFieldOptions.Representation.DOUBLE)
                  .buildNumberFacetField(),
          () ->
              NumericFieldDefinitionBuilder.builder()
                  .indexDoubles(false)
                  .indexIntegers(true)
                  .buildNumberFacetField(),
          () ->
              NumericFieldDefinitionBuilder.builder()
                  .indexDoubles(true)
                  .indexIntegers(false)
                  .buildNumberFacetField());
    }
  }
}
