package com.xgen.mongot.index.definition;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.load;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.definition.DateFieldDefinitionBuilder;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      DateFieldDefinitionTest.DeserializationTest.class,
      DateFieldDefinitionTest.SerializationTest.class,
      DateFieldDefinitionTest.DefinitionTest.class
    })
public class DateFieldDefinitionTest {

  @RunWith(Parameterized.class)
  public static class DeserializationTest {

    private static final String SUITE_NAME = "date-deserialization";
    private static final BsonDeserializationTestSuite<DateFieldDefinition> TEST_SUITE =
        fromDocument(DefinitionTests.RESOURCES_PATH, SUITE_NAME, DateFieldDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<DateFieldDefinition> testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<DateFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<DateFieldDefinition>>
        data() {
      return TEST_SUITE.withExamples(empty());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<DateFieldDefinition> empty() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "empty", DateFieldDefinitionBuilder.builder().build());
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {

    private static final String SUITE_NAME = "date-serialization";
    private static final BsonSerializationTestSuite<DateFieldDefinition> TEST_SUITE =
        load(DefinitionTests.RESOURCES_PATH, SUITE_NAME, DateFieldDefinition::fieldTypeToBson);

    private final BsonSerializationTestSuite.TestSpec<DateFieldDefinition> testSpec;

    public SerializationTest(BsonSerializationTestSuite.TestSpec<DateFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<DateFieldDefinition>> data() {
      return Arrays.asList(empty());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<DateFieldDefinition> empty() {
      return BsonSerializationTestSuite.TestSpec.create(
          "empty", DateFieldDefinitionBuilder.builder().build());
    }
  }

  public static class DefinitionTest {

    @Test
    public void testGetType() {
      DateFieldDefinition definition = DateFieldDefinitionBuilder.builder().build();
      Assert.assertEquals(FieldTypeDefinition.Type.DATE, definition.getType());
    }

    @Test
    public void testEquals() {
      TestUtils.assertEqualityGroups(() -> DateFieldDefinitionBuilder.builder().build());
    }
  }
}
