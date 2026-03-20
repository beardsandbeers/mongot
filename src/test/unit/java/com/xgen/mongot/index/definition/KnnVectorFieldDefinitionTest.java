package com.xgen.mongot.index.definition;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.load;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.definition.KnnVectorFieldDefinitionBuilder;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      KnnVectorFieldDefinitionTest.DeserializationTest.class,
      KnnVectorFieldDefinitionTest.SerializationTest.class,
      KnnVectorFieldDefinitionTest.DefinitionTest.class,
    })
public class KnnVectorFieldDefinitionTest {

  @RunWith(Parameterized.class)
  public static class DeserializationTest {

    private static final String SUITE_NAME = "knnVector-deserialization";
    private static final BsonDeserializationTestSuite<KnnVectorFieldDefinition> TEST_SUITE =
        fromDocument(
            DefinitionTests.RESOURCES_PATH, SUITE_NAME, KnnVectorFieldDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<KnnVectorFieldDefinition> testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<KnnVectorFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<KnnVectorFieldDefinition>>
        data() {
      return TEST_SUITE.withExamples(euclidean(), dotProduct(), cosine());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<KnnVectorFieldDefinition> euclidean() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "euclidean",
          KnnVectorFieldDefinitionBuilder.builder()
              .similarity(VectorSimilarity.EUCLIDEAN)
              .dimensions(100)
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<KnnVectorFieldDefinition> dotProduct() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "dotProduct",
          KnnVectorFieldDefinitionBuilder.builder()
              .similarity(VectorSimilarity.DOT_PRODUCT)
              .dimensions(200)
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<KnnVectorFieldDefinition> cosine() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "cosine",
          KnnVectorFieldDefinitionBuilder.builder()
              .similarity(VectorSimilarity.COSINE)
              .dimensions(300)
              .build());
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {

    private static final String SUITE_NAME = "knnVector-serialization";
    private static final BsonSerializationTestSuite<KnnVectorFieldDefinition> TEST_SUITE =
        load(DefinitionTests.RESOURCES_PATH, SUITE_NAME, KnnVectorFieldDefinition::fieldTypeToBson);

    private final BsonSerializationTestSuite.TestSpec<KnnVectorFieldDefinition> testSpec;

    public SerializationTest(
        BsonSerializationTestSuite.TestSpec<KnnVectorFieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<KnnVectorFieldDefinition>> data() {
      return Arrays.asList(euclidean(), dotProduct(), cosine());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<KnnVectorFieldDefinition> euclidean() {
      return BsonSerializationTestSuite.TestSpec.create(
          "euclidean",
          KnnVectorFieldDefinitionBuilder.builder()
              .similarity(VectorSimilarity.EUCLIDEAN)
              .dimensions(100)
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<KnnVectorFieldDefinition> dotProduct() {
      return BsonSerializationTestSuite.TestSpec.create(
          "dotProduct",
          KnnVectorFieldDefinitionBuilder.builder()
              .similarity(VectorSimilarity.DOT_PRODUCT)
              .dimensions(200)
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<KnnVectorFieldDefinition> cosine() {
      return BsonSerializationTestSuite.TestSpec.create(
          "cosine",
          KnnVectorFieldDefinitionBuilder.builder()
              .similarity(VectorSimilarity.COSINE)
              .dimensions(300)
              .build());
    }
  }

  public static class DefinitionTest {

    @Test
    public void testGetType() {
      var definition =
          KnnVectorFieldDefinitionBuilder.builder()
              .similarity(VectorSimilarity.EUCLIDEAN)
              .dimensions(100)
              .build();
      Assert.assertEquals(FieldTypeDefinition.Type.KNN_VECTOR, definition.getType());
    }

    @Test
    public void testGetSimilarity() {
      var definition =
          KnnVectorFieldDefinitionBuilder.builder()
              .similarity(VectorSimilarity.EUCLIDEAN)
              .dimensions(100)
              .build();
      Assert.assertEquals(VectorSimilarity.EUCLIDEAN, definition.specification().similarity());
    }

    @Test
    public void testGetDimensions() {
      var definition =
          KnnVectorFieldDefinitionBuilder.builder()
              .similarity(VectorSimilarity.EUCLIDEAN)
              .dimensions(100)
              .build();
      Assert.assertEquals(100, definition.specification().numDimensions());
    }

    @Test
    public void testEquals() {
      TestUtils.assertEqualityGroups(
          () ->
              KnnVectorFieldDefinitionBuilder.builder()
                  .similarity(VectorSimilarity.EUCLIDEAN)
                  .dimensions(100)
                  .build(),
          () ->
              KnnVectorFieldDefinitionBuilder.builder()
                  .similarity(VectorSimilarity.DOT_PRODUCT)
                  .dimensions(200)
                  .build(),
          () ->
              KnnVectorFieldDefinitionBuilder.builder()
                  .similarity(VectorSimilarity.COSINE)
                  .dimensions(300)
                  .build());
    }
  }
}
