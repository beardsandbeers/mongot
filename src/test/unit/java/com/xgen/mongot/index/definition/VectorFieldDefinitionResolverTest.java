package com.xgen.mongot.index.definition;

import static com.xgen.mongot.index.definition.VectorIndexFieldDefinition.Type;
import static com.xgen.mongot.index.definition.VectorSimilarity.EUCLIDEAN;

import com.google.common.truth.Truth;
import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import com.xgen.mongot.index.version.IndexFormatVersion;
import com.xgen.mongot.util.FieldPath;
import com.xgen.testing.mongot.index.definition.VectorIndexDefinitionBuilder;
import java.util.List;
import java.util.UUID;
import org.bson.types.ObjectId;
import org.junit.Assert;
import org.junit.Test;

public class VectorFieldDefinitionResolverTest {

  @Test
  public void testIsUsed() {
    var definition =
        VectorIndexDefinitionBuilder.builder()
            .indexId(new ObjectId())
            .name("test")
            .database("db")
            .lastObservedCollectionName("col")
            .collectionUuid(UUID.randomUUID())
            .numPartitions(1)
            .setFields(
                List.of(
                    new VectorDataFieldDefinition(
                        FieldPath.parse("a.b.c"),
                        new VectorFieldSpecification(
                            100,
                            EUCLIDEAN,
                            VectorQuantization.NONE,
                            new VectorIndexingAlgorithm.HnswIndexingAlgorithm())),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("d")),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("e.f"))))
            .build();

    var resolver = new VectorFieldDefinitionResolver(definition, IndexFormatVersion.CURRENT);
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("a")));
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("a.b")));
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("a.b.c")));
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("d")));
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("e")));
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("e.f")));

    Assert.assertFalse(resolver.isUsed(FieldPath.parse("a.b.x")));
    Assert.assertFalse(resolver.isUsed(FieldPath.parse("d.x")));
    Assert.assertFalse(resolver.isUsed(FieldPath.parse("x")));
  }

  @Test
  public void testIsIndexed() {
    var definition =
        VectorIndexDefinitionBuilder.builder()
            .indexId(new ObjectId())
            .name("test")
            .database("db")
            .lastObservedCollectionName("col")
            .collectionUuid(UUID.randomUUID())
            .numPartitions(1)
            .setFields(
                List.of(
                    new VectorDataFieldDefinition(
                        FieldPath.parse("a.b.c"),
                        new VectorFieldSpecification(
                            100,
                            EUCLIDEAN,
                            VectorQuantization.NONE,
                            new VectorIndexingAlgorithm.HnswIndexingAlgorithm())),
                    new VectorTextFieldDefinition(FieldPath.parse("a.b.d")),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("d")),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("e.f"))))
            .build();

    var resolver = new VectorFieldDefinitionResolver(definition, IndexFormatVersion.CURRENT);

    // check exact path returns true
    Assert.assertTrue(resolver.isIndexed(FieldPath.parse("a.b.c"), Type.VECTOR));
    Assert.assertTrue(resolver.isIndexed(FieldPath.parse("a.b.d"), Type.TEXT));
    Assert.assertTrue(resolver.isIndexed(FieldPath.parse("d"), Type.FILTER));
    Assert.assertTrue(resolver.isIndexed(FieldPath.parse("e.f"), Type.FILTER));

    // check above and below the exact path returns false
    Assert.assertFalse(resolver.isIndexed(FieldPath.parse("a.b.c.d"), Type.VECTOR));
    Assert.assertFalse(resolver.isIndexed(FieldPath.parse("e"), Type.FILTER));
    Assert.assertFalse(resolver.isIndexed(FieldPath.parse("a.b.c"), Type.TEXT));

    // check exact path but type mismatch returns false
    Assert.assertFalse(resolver.isIndexed(FieldPath.parse("a.b.c"), Type.FILTER));
    Assert.assertFalse(resolver.isIndexed(FieldPath.parse("e.f"), Type.VECTOR));
    Assert.assertFalse(resolver.isIndexed(FieldPath.parse("a.b.d"), Type.VECTOR));
  }

  @Test
  public void testGetVectorFieldSpecification() {
    VectorFieldSpecification fieldSpecification =
        new VectorFieldSpecification(
            100,
            EUCLIDEAN,
            VectorQuantization.NONE,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm());

    var vectorIndexDefinition =
        VectorIndexDefinitionBuilder.builder()
            .indexId(new ObjectId())
            .name("test")
            .database("db")
            .lastObservedCollectionName("col")
            .collectionUuid(UUID.randomUUID())
            .numPartitions(1)
            .setFields(
                List.of(
                    new VectorDataFieldDefinition(
                        FieldPath.parse("foo.vector"), fieldSpecification),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("bar.filter"))))
            .build();

    var resolver =
        new VectorFieldDefinitionResolver(vectorIndexDefinition, IndexFormatVersion.CURRENT);
    var existingVector = resolver.getVectorFieldSpecification(FieldPath.parse("foo.vector"));
    Assert.assertEquals(fieldSpecification, existingVector.orElseThrow());

    var nonExistingVector = resolver.getVectorFieldSpecification(FieldPath.parse("foo.vector2"));
    Assert.assertTrue(nonExistingVector.isEmpty());

    var incorrectTypeDefinition =
        resolver.getVectorFieldSpecification(FieldPath.parse("bar.filter"));
    Assert.assertTrue(incorrectTypeDefinition.isEmpty());
  }

  @Test
  public void testIsUsed_WithNestedVectorFields() {
    // Nested vector path under nestedRoot "reviews"; resolver uses full path for lookups
    FieldPath nestedVectorPath = FieldPath.parse("reviews.message.embedding");

    var definition =
        VectorIndexDefinitionBuilder.builder()
            .indexId(new ObjectId())
            .name("test")
            .database("db")
            .lastObservedCollectionName("col")
            .collectionUuid(UUID.randomUUID())
            .numPartitions(1)
            .nestedRoot("reviews")
            .setFields(
                List.of(
                    new VectorDataFieldDefinition(
                        nestedVectorPath,
                        new VectorFieldSpecification(
                            100,
                            EUCLIDEAN,
                            VectorQuantization.NONE,
                            new VectorIndexingAlgorithm.HnswIndexingAlgorithm())),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();

    var resolver = new VectorFieldDefinitionResolver(definition, IndexFormatVersion.CURRENT);

    // Test that all ancestor paths of the nested vector are marked as used
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("reviews")));
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("reviews.message")));
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("reviews.message.embedding")));

    // Test filter field
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("category")));

    // Test non-existent paths
    Assert.assertFalse(resolver.isUsed(FieldPath.parse("reviews.other")));
    Assert.assertFalse(resolver.isUsed(FieldPath.parse("products")));
  }

  @Test
  public void testIsIndexed_WithNestedVectorFields() {
    FieldPath nestedVectorPath = FieldPath.parse("items.embedding");

    var definition =
        VectorIndexDefinitionBuilder.builder()
            .indexId(new ObjectId())
            .name("test")
            .database("db")
            .lastObservedCollectionName("col")
            .collectionUuid(UUID.randomUUID())
            .numPartitions(1)
            .nestedRoot("items")
            .setFields(
                List.of(
                    new VectorDataFieldDefinition(
                        nestedVectorPath,
                        new VectorFieldSpecification(
                            256,
                            EUCLIDEAN,
                            VectorQuantization.NONE,
                            new VectorIndexingAlgorithm.HnswIndexingAlgorithm()))))
            .build();

    var resolver = new VectorFieldDefinitionResolver(definition, IndexFormatVersion.CURRENT);

    // The full nested vector path should be indexed as VECTOR type
    Assert.assertTrue(resolver.isIndexed(FieldPath.parse("items.embedding"), Type.VECTOR));
    Assert.assertFalse(resolver.isIndexed(FieldPath.parse("items.embedding"), Type.FILTER));

    // Parent path should not be indexed as VECTOR type
    Assert.assertFalse(resolver.isIndexed(FieldPath.parse("items"), Type.VECTOR));
  }

  @Test
  public void testGetVectorFieldSpecification_WithNestedFields() {
    VectorFieldSpecification nestedSpec =
        new VectorFieldSpecification(
            512,
            EUCLIDEAN,
            VectorQuantization.SCALAR,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm());

    FieldPath nestedVectorPath = FieldPath.parse("sections.paragraph.vector");

    var definition =
        VectorIndexDefinitionBuilder.builder()
            .indexId(new ObjectId())
            .name("test")
            .database("db")
            .lastObservedCollectionName("col")
            .collectionUuid(UUID.randomUUID())
            .numPartitions(1)
            .nestedRoot("sections")
            .setFields(List.of(new VectorDataFieldDefinition(nestedVectorPath, nestedSpec)))
            .build();

    var resolver = new VectorFieldDefinitionResolver(definition, IndexFormatVersion.CURRENT);

    // Should be able to retrieve the specification using the full nested vector path
    var spec = resolver.getVectorFieldSpecification(FieldPath.parse("sections.paragraph.vector"));
    Truth.assertThat(spec).isPresent();
    Assert.assertEquals(nestedSpec, spec.get());

    // Should not find specification for partial paths
    var partialSpec = resolver.getVectorFieldSpecification(FieldPath.parse("sections"));
    Truth.assertThat(partialSpec).isEmpty();

    var partialSpec2 = resolver.getVectorFieldSpecification(FieldPath.parse("sections.paragraph"));
    Truth.assertThat(partialSpec2).isEmpty();
  }

  @Test
  public void testMixedSimpleAndNestedVectorFields() {
    VectorFieldSpecification simpleSpec =
        new VectorFieldSpecification(
            128,
            EUCLIDEAN,
            VectorQuantization.NONE,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm());

    VectorFieldSpecification nestedSpec =
        new VectorFieldSpecification(
            256,
            EUCLIDEAN,
            VectorQuantization.SCALAR,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm());

    FieldPath nestedVectorPath = FieldPath.parse("reviews.embedding");

    var definition =
        VectorIndexDefinitionBuilder.builder()
            .indexId(new ObjectId())
            .name("test")
            .database("db")
            .lastObservedCollectionName("col")
            .collectionUuid(UUID.randomUUID())
            .numPartitions(1)
            .nestedRoot("reviews")
            .setFields(
                List.of(
                    new VectorDataFieldDefinition(FieldPath.parse("simpleVector"), simpleSpec),
                    new VectorDataFieldDefinition(nestedVectorPath, nestedSpec),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();

    var resolver = new VectorFieldDefinitionResolver(definition, IndexFormatVersion.CURRENT);

    // Test simple vector field
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("simpleVector")));
    Assert.assertTrue(resolver.isIndexed(FieldPath.parse("simpleVector"), Type.VECTOR));
    var mainSpec = resolver.getVectorFieldSpecification(FieldPath.parse("simpleVector"));
    Assert.assertEquals(simpleSpec, mainSpec.orElseThrow());

    // Test nested vector field
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("reviews")));
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("reviews.embedding")));
    Assert.assertTrue(resolver.isIndexed(FieldPath.parse("reviews.embedding"), Type.VECTOR));
    var reviewSpec = resolver.getVectorFieldSpecification(FieldPath.parse("reviews.embedding"));
    Assert.assertEquals(nestedSpec, reviewSpec.orElseThrow());

    // Test filter field
    Assert.assertTrue(resolver.isUsed(FieldPath.parse("category")));
    Assert.assertTrue(resolver.isIndexed(FieldPath.parse("category"), Type.FILTER));
  }
}
