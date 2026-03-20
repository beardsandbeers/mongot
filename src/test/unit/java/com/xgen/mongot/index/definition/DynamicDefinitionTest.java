package com.xgen.mongot.index.definition;

import static com.xgen.testing.BsonDeserializationTestSuite.fromValue;
import static com.xgen.testing.BsonSerializationTestSuite.load;

import com.google.common.truth.Truth;
import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      DynamicDefinitionTest.DeserializationTest.class,
      DynamicDefinitionTest.SerializationTest.class,
      DynamicDefinitionTest.DefinitionTest.class,
    })
public class DynamicDefinitionTest {
  @RunWith(Parameterized.class)
  public static class DeserializationTest {
    private static final String SUITE_NAME = "dynamic-deserialization";
    private static final BsonDeserializationTestSuite<DynamicDefinition> TEST_SUITE =
        fromValue(DefinitionTests.RESOURCES_PATH, SUITE_NAME, DynamicDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<DynamicDefinition> testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<DynamicDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<DynamicDefinition>> data() {
      return TEST_SUITE.withExamples(bool(), typeSet());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<DynamicDefinition> bool() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "boolean", new DynamicDefinition.Boolean(true));
    }

    private static BsonDeserializationTestSuite.ValidSpec<DynamicDefinition> typeSet() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "typeSet", new DynamicDefinition.Document("foo"));
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {
    private static final String SUITE_NAME = "dynamic-serialization";
    private static final BsonSerializationTestSuite<DynamicDefinition> TEST_SUITE =
        load(DefinitionTests.RESOURCES_PATH, SUITE_NAME, DynamicDefinition::toBson);

    private final BsonSerializationTestSuite.TestSpec<DynamicDefinition> testSpec;

    public SerializationTest(BsonSerializationTestSuite.TestSpec<DynamicDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<DynamicDefinition>> data() {
      return Arrays.asList(bool(), typeSet());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<DynamicDefinition> bool() {
      return BsonSerializationTestSuite.TestSpec.create(
          "boolean", new DynamicDefinition.Boolean(true));
    }

    private static BsonSerializationTestSuite.TestSpec<DynamicDefinition> typeSet() {
      return BsonSerializationTestSuite.TestSpec.create(
          "typeSet", new DynamicDefinition.Document("foo"));
    }
  }

  public static class DefinitionTest {
    @Test
    public void isEnabled_equalityChecks_passes() {
      Truth.assertThat(new DynamicDefinition.Boolean(true).isEnabled()).isTrue();
      Truth.assertThat(new DynamicDefinition.Boolean(false).isEnabled()).isFalse();
      Truth.assertThat(new DynamicDefinition.Document("foo").isEnabled()).isTrue();
    }
  }
}
