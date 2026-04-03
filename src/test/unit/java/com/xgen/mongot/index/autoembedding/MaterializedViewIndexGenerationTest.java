package com.xgen.mongot.index.autoembedding;

import static com.xgen.testing.mongot.mock.index.MaterializedViewIndex.mockMatViewIndexGeneration;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xgen.mongot.embedding.providers.configs.EmbeddingModelCatalog;
import com.xgen.mongot.embedding.providers.configs.EmbeddingModelConfig;
import com.xgen.mongot.embedding.providers.configs.EmbeddingServiceConfig;
import com.xgen.mongot.index.definition.MaterializedViewIndexDefinitionGeneration;
import com.xgen.mongot.index.definition.StoredSourceDefinition;
import com.xgen.mongot.index.definition.VectorAutoEmbedFieldDefinition;
import com.xgen.mongot.index.definition.VectorFieldSpecification;
import com.xgen.mongot.index.definition.VectorIndexDefinition;
import com.xgen.mongot.index.definition.VectorIndexFilterFieldDefinition;
import com.xgen.mongot.index.definition.VectorIndexingAlgorithm;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.definition.quantization.VectorAutoEmbedQuantization;
import com.xgen.mongot.index.version.Generation;
import com.xgen.mongot.index.version.MaterializedViewGeneration;
import com.xgen.mongot.index.version.MaterializedViewGenerationId;
import com.xgen.mongot.util.FieldPath;
import com.xgen.testing.mongot.index.definition.VectorIndexDefinitionBuilder;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link MaterializedViewIndexGeneration#needsNewMatViewGenerator}. */
public class MaterializedViewIndexGenerationTest {

  private static final ObjectId INDEX_ID = new ObjectId();
  private static final MaterializedViewGenerationId GENERATION_ID =
      new MaterializedViewGenerationId(
          INDEX_ID, new MaterializedViewGeneration(Generation.CURRENT));

  @Before
  public void setupRegistry() {
    EmbeddingModelCatalog.registerModelConfig(
        "voyage-3-large",
        EmbeddingModelConfig.create(
            "voyage-3-large",
            EmbeddingServiceConfig.EmbeddingProvider.VOYAGE,
            new EmbeddingServiceConfig.EmbeddingConfig(
                Optional.of("us-east-1"),
                new EmbeddingServiceConfig.VoyageModelConfig(
                    Optional.of(512),
                    Optional.of(EmbeddingServiceConfig.TruncationOption.START),
                    Optional.of(100),
                    Optional.of(1000)),
                new EmbeddingServiceConfig.ErrorHandlingConfig(50, 50L, 10L, 0.1),
                new EmbeddingServiceConfig.VoyageEmbeddingCredentials(
                    "token123", "2024-10-15T22:32:20.925Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                true,
                Optional.empty())));
  }

  /**
   * When definitions differ only by HNSW options (V1 vs V2), we need a new generator so that the
   * new generator is linked to the V2 index and V2's status gets updated (replace path, same as
   * filter-only).
   */
  @Test
  public void needsNewMatViewGenerator_definitionsDifferOnlyByHnswOptions_returnsTrue() {
    VectorAutoEmbedFieldDefinition autoEmbedDefault =
        new VectorAutoEmbedFieldDefinition(
            "voyage-3-large",
            "text",
            FieldPath.parse("desc"),
            512,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    VectorAutoEmbedFieldDefinition autoEmbedCustomHnsw =
        new VectorAutoEmbedFieldDefinition(
            "voyage-3-large",
            "text",
            FieldPath.parse("desc"),
            512,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(
                new VectorFieldSpecification.HnswOptions(32, 200)));

    VectorIndexDefinition defV1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    autoEmbedDefault,
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinitionBuilder builder2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .name(defV1.getName())
            .database(defV1.getDatabase())
            .lastObservedCollectionName(defV1.getLastObservedCollectionName())
            .collectionUuid(defV1.getCollectionUuid())
            .numPartitions(defV1.getNumPartitions())
            .indexFeatureVersion(defV1.getParsedIndexFeatureVersion())
            .withDefinitionVersion(Optional.of(2L));
    defV1.getView().ifPresent(builder2::view);
    defV1.getNestedRoot().ifPresent(builder2::nestedRoot);
    if (!defV1.getStoredSource().equals(StoredSourceDefinition.defaultValue())) {
      builder2.storedSource(defV1.getStoredSource());
    }
    VectorIndexDefinition defV2 =
        builder2
            .setFields(
                List.of(
                    autoEmbedCustomHnsw,
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();

    MaterializedViewIndexDefinitionGeneration defGen1 =
        new MaterializedViewIndexDefinitionGeneration(defV1, GENERATION_ID.generation);
    MaterializedViewIndexDefinitionGeneration defGen2 =
        new MaterializedViewIndexDefinitionGeneration(defV2, GENERATION_ID.generation);

    MaterializedViewIndexGeneration oldGen = mockMatViewIndexGeneration(defGen1);
    MaterializedViewIndexGeneration newGen = mockMatViewIndexGeneration(defGen2);

    assertTrue(oldGen.needsNewMatViewGenerator(newGen));
  }

  /** Same definition version (e.g. re-apply or fell off oplog): reuse, no new generator. */
  @Test
  public void needsNewMatViewGenerator_sameDefinitionVersion_returnsFalse() {
    VectorAutoEmbedFieldDefinition autoEmbed =
        new VectorAutoEmbedFieldDefinition(
            "voyage-3-large",
            "text",
            FieldPath.parse("desc"),
            512,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    VectorIndexDefinition def =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    autoEmbed, new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();

    MaterializedViewIndexDefinitionGeneration defGen =
        new MaterializedViewIndexDefinitionGeneration(def, GENERATION_ID.generation);
    MaterializedViewIndexGeneration gen = mockMatViewIndexGeneration(defGen);

    assertFalse(gen.needsNewMatViewGenerator(gen));
  }

  /** New generation has older definition version: reuse, no new generator. */
  @Test
  public void needsNewMatViewGenerator_olderDefinitionVersion_returnsFalse() {
    VectorAutoEmbedFieldDefinition autoEmbed =
        new VectorAutoEmbedFieldDefinition(
            "voyage-3-large",
            "text",
            FieldPath.parse("desc"),
            512,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    VectorIndexDefinition defV1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    autoEmbed, new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinition defV2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .setFields(
                List.of(
                    autoEmbed, new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();

    MaterializedViewIndexDefinitionGeneration defGen2 =
        new MaterializedViewIndexDefinitionGeneration(defV2, GENERATION_ID.generation);
    MaterializedViewIndexDefinitionGeneration defGen1 =
        new MaterializedViewIndexDefinitionGeneration(defV1, GENERATION_ID.generation);
    MaterializedViewIndexGeneration oldGen = mockMatViewIndexGeneration(defGen2);
    MaterializedViewIndexGeneration newGen = mockMatViewIndexGeneration(defGen1);

    assertFalse(oldGen.needsNewMatViewGenerator(newGen));
  }

  @Test
  public void needsNewMatViewGenerator_definitionsDifferByModel_returnsTrue() {
    EmbeddingModelCatalog.registerModelConfig(
        "voyage-3.5",
        EmbeddingModelConfig.create(
            "voyage-3.5",
            EmbeddingServiceConfig.EmbeddingProvider.VOYAGE,
            new EmbeddingServiceConfig.EmbeddingConfig(
                Optional.of("us-east-1"),
                new EmbeddingServiceConfig.VoyageModelConfig(
                    Optional.of(1024), Optional.empty(), Optional.of(100), Optional.of(1000)),
                new EmbeddingServiceConfig.ErrorHandlingConfig(50, 50L, 10L, 0.1),
                new EmbeddingServiceConfig.VoyageEmbeddingCredentials(
                    "token123", "2024-10-15T22:32:20.925Z"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                true,
                Optional.empty())));
    VectorAutoEmbedFieldDefinition autoEmbedVoyage3 =
        new VectorAutoEmbedFieldDefinition(
            "voyage-3-large",
            "text",
            FieldPath.parse("desc"),
            512,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    VectorAutoEmbedFieldDefinition autoEmbedVoyage35 =
        new VectorAutoEmbedFieldDefinition(
            "voyage-3.5",
            "text",
            FieldPath.parse("desc"),
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);

    VectorIndexDefinition defV1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    autoEmbedVoyage3,
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinitionBuilder builder2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .name(defV1.getName())
            .database(defV1.getDatabase())
            .lastObservedCollectionName(defV1.getLastObservedCollectionName())
            .collectionUuid(defV1.getCollectionUuid())
            .numPartitions(defV1.getNumPartitions())
            .indexFeatureVersion(defV1.getParsedIndexFeatureVersion())
            .withDefinitionVersion(Optional.of(2L));
    defV1.getView().ifPresent(builder2::view);
    defV1.getNestedRoot().ifPresent(builder2::nestedRoot);
    if (!defV1.getStoredSource().equals(StoredSourceDefinition.defaultValue())) {
      builder2.storedSource(defV1.getStoredSource());
    }
    VectorIndexDefinition defV2 =
        builder2
            .setFields(
                List.of(
                    autoEmbedVoyage35,
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();

    MaterializedViewIndexDefinitionGeneration defGen1 =
        new MaterializedViewIndexDefinitionGeneration(defV1, GENERATION_ID.generation);
    MaterializedViewIndexDefinitionGeneration defGen2 =
        new MaterializedViewIndexDefinitionGeneration(defV2, GENERATION_ID.generation);

    MaterializedViewIndexGeneration oldGen = mockMatViewIndexGeneration(defGen1);
    MaterializedViewIndexGeneration newGen = mockMatViewIndexGeneration(defGen2);

    assertTrue(oldGen.needsNewMatViewGenerator(newGen));
  }
}
