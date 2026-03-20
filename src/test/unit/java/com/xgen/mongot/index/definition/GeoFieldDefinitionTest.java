package com.xgen.mongot.index.definition;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.load;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.definition.GeoFieldDefinitionBuilder;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      GeoFieldDefinitionTest.DeserializationTest.class,
      GeoFieldDefinitionTest.SerializationTest.class,
      GeoFieldDefinitionTest.DefinitionTest.class,
    })
public class GeoFieldDefinitionTest {

  @RunWith(Parameterized.class)
  public static class DeserializationTest {

    private static final String SUITE_NAME = "geo-deserialization";
    private static final BsonDeserializationTestSuite<GeoFieldDefinition> TEST_SUITE =
        fromDocument(DefinitionTests.RESOURCES_PATH, SUITE_NAME, GeoFieldDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<GeoFieldDefinition> testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<GeoFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<GeoFieldDefinition>>
        data() {
      return TEST_SUITE.withExamples(empty(), explicitIndexShapes());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<GeoFieldDefinition> empty() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "empty", GeoFieldDefinitionBuilder.builder().build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<GeoFieldDefinition>
        explicitIndexShapes() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "explicit indexShapes", GeoFieldDefinitionBuilder.builder().indexShapes(true).build());
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {

    private static final String SUITE_NAME = "geo-serialization";
    private static final BsonSerializationTestSuite<GeoFieldDefinition> TEST_SUITE =
        load(DefinitionTests.RESOURCES_PATH, SUITE_NAME, GeoFieldDefinition::fieldTypeToBson);

    private final BsonSerializationTestSuite.TestSpec<GeoFieldDefinition> testSpec;

    public SerializationTest(BsonSerializationTestSuite.TestSpec<GeoFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<GeoFieldDefinition>> data() {
      return Arrays.asList(simple());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<GeoFieldDefinition> simple() {
      return BsonSerializationTestSuite.TestSpec.create(
          "simple", GeoFieldDefinitionBuilder.builder().indexShapes(true).build());
    }
  }

  public static class DefinitionTest {

    @Test
    public void testGetType() {
      GeoFieldDefinition definition = GeoFieldDefinitionBuilder.builder().build();
      Assert.assertEquals(FieldTypeDefinition.Type.GEO, definition.getType());
    }

    @Test
    public void testEquals() {
      TestUtils.assertEqualityGroups(
          () -> GeoFieldDefinitionBuilder.builder().indexShapes(false).build(),
          () -> GeoFieldDefinitionBuilder.builder().indexShapes(true).build());
    }
  }
}
