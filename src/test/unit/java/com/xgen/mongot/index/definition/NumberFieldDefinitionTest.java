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
      NumberFieldDefinitionTest.DeserializationTest.class,
      NumberFieldDefinitionTest.SerializationTest.class,
      NumberFieldDefinitionTest.DefinitionTest.class,
    })
public class NumberFieldDefinitionTest {

  @RunWith(Parameterized.class)
  public static class DeserializationTest {

    private static final String SUITE_NAME = "number-deserialization";
    private static final BsonDeserializationTestSuite<NumberFieldDefinition> TEST_SUITE =
        fromDocument(DefinitionTests.RESOURCES_PATH, SUITE_NAME, NumberFieldDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<NumberFieldDefinition> testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<NumberFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<NumberFieldDefinition>>
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

    private static BsonDeserializationTestSuite.ValidSpec<NumberFieldDefinition> empty() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "empty", NumericFieldDefinitionBuilder.builder().buildNumberField());
    }

    private static BsonDeserializationTestSuite.ValidSpec<NumberFieldDefinition>
        explicitDoubleRepresentation() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit double representation",
          NumericFieldDefinitionBuilder.builder()
              .representation(NumericFieldOptions.Representation.DOUBLE)
              .buildNumberField());
    }

    private static BsonDeserializationTestSuite.ValidSpec<NumberFieldDefinition>
        explicitInt64Representation() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit int64 representation",
          NumericFieldDefinitionBuilder.builder()
              .representation(NumericFieldOptions.Representation.INT64)
              .buildNumberField());
    }

    private static BsonDeserializationTestSuite.ValidSpec<NumberFieldDefinition>
        explicitIndexDoubles() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit indexDoubles",
          NumericFieldDefinitionBuilder.builder().indexDoubles(false).buildNumberField());
    }

    private static BsonDeserializationTestSuite.ValidSpec<NumberFieldDefinition>
        explicitIndexIntegers() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit indexIntegers",
          NumericFieldDefinitionBuilder.builder().indexIntegers(false).buildNumberField());
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {

    private static final String SUITE_NAME = "number-serialization";
    private static final BsonSerializationTestSuite<NumberFieldDefinition> TEST_SUITE =
        load(DefinitionTests.RESOURCES_PATH, SUITE_NAME, NumberFieldDefinition::fieldTypeToBson);

    private final BsonSerializationTestSuite.TestSpec<NumberFieldDefinition> testSpec;

    public SerializationTest(BsonSerializationTestSuite.TestSpec<NumberFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<NumberFieldDefinition>> data() {
      return Arrays.asList(doubleRepresentation(), int64Representation());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<NumberFieldDefinition>
        doubleRepresentation() {
      return BsonSerializationTestSuite.TestSpec.create(
          "double representation",
          NumericFieldDefinitionBuilder.builder()
              .representation(NumericFieldOptions.Representation.DOUBLE)
              .buildNumberField());
    }

    private static BsonSerializationTestSuite.TestSpec<NumberFieldDefinition>
        int64Representation() {
      return BsonSerializationTestSuite.TestSpec.create(
          "int64 representation",
          NumericFieldDefinitionBuilder.builder()
              .representation(NumericFieldOptions.Representation.INT64)
              .buildNumberField());
    }
  }

  public static class DefinitionTest {

    @Test
    public void testGetType() {
      NumberFieldDefinition definition = NumericFieldDefinitionBuilder.builder().buildNumberField();
      Assert.assertEquals(FieldTypeDefinition.Type.NUMBER, definition.getType());
    }

    @Test
    public void testEquals() {
      TestUtils.assertEqualityGroups(
          () ->
              NumericFieldDefinitionBuilder.builder()
                  .representation(NumericFieldOptions.Representation.INT64)
                  .buildNumberField(),
          () ->
              NumericFieldDefinitionBuilder.builder()
                  .representation(NumericFieldOptions.Representation.DOUBLE)
                  .buildNumberField(),
          () ->
              NumericFieldDefinitionBuilder.builder()
                  .indexDoubles(false)
                  .indexIntegers(true)
                  .buildNumberField(),
          () ->
              NumericFieldDefinitionBuilder.builder()
                  .indexDoubles(true)
                  .indexIntegers(false)
                  .buildNumberField());
    }
  }
}
