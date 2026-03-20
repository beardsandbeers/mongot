package com.xgen.mongot.index.definition;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.load;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.definition.DateFacetFieldDefinitionBuilder;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      DateFacetFieldDefinitionTest.DeserializationTest.class,
      DateFacetFieldDefinitionTest.SerializationTest.class,
      DateFacetFieldDefinitionTest.DefinitionTest.class
    })
public class DateFacetFieldDefinitionTest {

  @RunWith(Parameterized.class)
  public static class DeserializationTest {

    private static final String SUITE_NAME = "date-facet-deserialization";
    private static final BsonDeserializationTestSuite<DateFacetFieldDefinition> TEST_SUITE =
        fromDocument(
            DefinitionTests.RESOURCES_PATH, SUITE_NAME, DateFacetFieldDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<DateFacetFieldDefinition> testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<DateFacetFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<DateFacetFieldDefinition>>
        data() {
      return TEST_SUITE.withExamples(empty());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<DateFacetFieldDefinition> empty() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "empty", DateFacetFieldDefinitionBuilder.builder().build());
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {

    private static final String SUITE_NAME = "date-facet-serialization";
    private static final BsonSerializationTestSuite<DateFacetFieldDefinition> TEST_SUITE =
        load(DefinitionTests.RESOURCES_PATH, SUITE_NAME, DateFacetFieldDefinition::fieldTypeToBson);

    private final BsonSerializationTestSuite.TestSpec<DateFacetFieldDefinition> testSpec;

    public SerializationTest(
        BsonSerializationTestSuite.TestSpec<DateFacetFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<DateFacetFieldDefinition>> data() {
      return Arrays.asList(empty());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<DateFacetFieldDefinition> empty() {
      return BsonSerializationTestSuite.TestSpec.create(
          "empty", DateFacetFieldDefinitionBuilder.builder().build());
    }
  }

  public static class DefinitionTest {

    @Test
    public void testGetType() {
      DateFacetFieldDefinition definition = DateFacetFieldDefinitionBuilder.builder().build();
      Assert.assertEquals(FieldTypeDefinition.Type.DATE_FACET, definition.getType());
    }

    @Test
    public void testEquals() {
      TestUtils.assertEqualityGroups(() -> DateFacetFieldDefinitionBuilder.builder().build());
    }
  }
}
