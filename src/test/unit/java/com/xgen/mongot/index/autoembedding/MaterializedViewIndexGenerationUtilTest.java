package com.xgen.mongot.index.autoembedding;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xgen.mongot.index.definition.VectorAutoEmbedFieldDefinition;
import com.xgen.mongot.index.definition.VectorFieldSpecification;
import com.xgen.mongot.index.definition.VectorIndexDefinition;
import com.xgen.mongot.index.definition.VectorIndexFieldDefinition;
import com.xgen.mongot.index.definition.VectorIndexFilterFieldDefinition;
import com.xgen.mongot.index.definition.VectorIndexingAlgorithm;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.definition.quantization.VectorAutoEmbedQuantization;
import com.xgen.mongot.util.FieldPath;
import com.xgen.testing.mongot.index.definition.VectorIndexDefinitionBuilder;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.Test;

/** Unit tests for {@link MaterializedViewIndexGenerationUtil#skipInitialSync}. */
public class MaterializedViewIndexGenerationUtilTest {

  private static final ObjectId INDEX_ID = new ObjectId();

  @Test
  public void skipInitialSync_sameDefinitionDifferentOrder_returnsTrue() {
    VectorIndexFieldDefinition autoEmbedFieldDefinition =
        new VectorAutoEmbedFieldDefinition(
            "voyage-3-large",
            "text",
            FieldPath.parse("desc"),
            512,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    autoEmbedFieldDefinition,
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category")),
                    autoEmbedFieldDefinition))
            .build();
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
  }

  @Test
  public void skipInitialSync_onlyIncreaseDefinitionVersion_returnsFalse() {
    List<VectorIndexFieldDefinition> fields =
        List.of(
            new VectorAutoEmbedFieldDefinition(
                "voyage-3-large",
                "text",
                FieldPath.parse("desc"),
                512,
                VectorSimilarity.DOT_PRODUCT,
                VectorAutoEmbedQuantization.FLOAT),
            new VectorIndexFilterFieldDefinition(FieldPath.parse("category")));
    VectorIndexDefinition defVersion1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(fields)
            .build();
    VectorIndexDefinition defVersion2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .setFields(fields)
            .build();
    assertFalse(MaterializedViewIndexGenerationUtil.skipInitialSync(defVersion1, defVersion2));
  }

  @Test
  public void skipInitialSync_redefineHnswOptions_returnsTrue() {
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT,
                        new VectorIndexingAlgorithm.HnswIndexingAlgorithm(
                            new VectorFieldSpecification.HnswOptions(32, 200))),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def2, def1));
  }

  @Test
  public void skipInitialSync_onlyIndexingMethodDiffer_returnsTrue() {
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT,
                        new VectorIndexingAlgorithm.FlatIndexingAlgorithm()),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def2, def1));
  }

  @Test
  public void skipInitialSync_onlySimilarityDiffer_returnsTrue() {
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.COSINE,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def2, def1));
  }

  @Test
  public void skipInitialSync_redefineQuantization_returnsFalse() {
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.SCALAR),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    assertFalse(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
    assertFalse(MaterializedViewIndexGenerationUtil.skipInitialSync(def2, def1));
  }

  @Test
  public void skipInitialSync_redefinePath_returnsFalse() {
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("title"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    assertFalse(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
  }

  @Test
  public void skipInitialSync_redefineFilterField_returnsFalse() {
    VectorIndexFieldDefinition autoEmbedFieldDefinition =
        new VectorAutoEmbedFieldDefinition(
            "voyage-3-large",
            "text",
            FieldPath.parse("desc"),
            512,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    autoEmbedFieldDefinition,
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    autoEmbedFieldDefinition,
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("genre"))))
            .build();
    assertFalse(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
  }

  @Test
  public void skipInitialSync_onlyIncreaseNumPartitions_returnsTrue() {
    List<VectorIndexFieldDefinition> fields =
        List.of(
            new VectorAutoEmbedFieldDefinition(
                "voyage-3-large",
                "text",
                FieldPath.parse("desc"),
                512,
                VectorSimilarity.DOT_PRODUCT,
                VectorAutoEmbedQuantization.FLOAT),
            new VectorIndexFilterFieldDefinition(FieldPath.parse("category")));
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .numPartitions(2)
            .setFields(fields)
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .numPartitions(3)
            .setFields(fields)
            .build();
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def2, def1));
  }

  @Test
  public void skipInitialSync_firstAutoEmbedLuceneOnlySecondQuantization_returnsFalse() {
    VectorIndexFieldDefinition filter =
        new VectorIndexFilterFieldDefinition(FieldPath.parse("category"));
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("title"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    filter))
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT,
                        new VectorIndexingAlgorithm.HnswIndexingAlgorithm(
                            new VectorFieldSpecification.HnswOptions(32, 200))),
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("title"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.SCALAR),
                    filter))
            .build();
    assertFalse(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
  }

  @Test
  public void skipInitialSync_onlyIncreaseIndexFeatureVersion_returnsTrue() {
    List<VectorIndexFieldDefinition> fields =
        List.of(
            new VectorAutoEmbedFieldDefinition(
                "voyage-3-large",
                "text",
                FieldPath.parse("desc"),
                512,
                VectorSimilarity.DOT_PRODUCT,
                VectorAutoEmbedQuantization.FLOAT),
            new VectorIndexFilterFieldDefinition(FieldPath.parse("category")));
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .indexFeatureVersion(2)
            .setFields(fields)
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .indexFeatureVersion(3)
            .setFields(fields)
            .build();
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
    assertTrue(MaterializedViewIndexGenerationUtil.skipInitialSync(def2, def1));
  }

  @Test
  public void skipInitialSync_redefineNumDimensions_returnsFalse() {
    // Using different models with different dimensions (voyage-3-large=512, voyage-3-small=256)
    VectorIndexDefinition def1 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(1L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-large",
                        "text",
                        FieldPath.parse("desc"),
                        512,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    VectorIndexDefinition def2 =
        VectorIndexDefinitionBuilder.builder()
            .indexId(INDEX_ID)
            .withDefinitionVersion(Optional.of(2L))
            .setFields(
                List.of(
                    new VectorAutoEmbedFieldDefinition(
                        "voyage-3-small",
                        "text",
                        FieldPath.parse("desc"),
                        256,
                        VectorSimilarity.DOT_PRODUCT,
                        VectorAutoEmbedQuantization.FLOAT),
                    new VectorIndexFilterFieldDefinition(FieldPath.parse("category"))))
            .build();
    // Different numDimensions (via different model) should require resync
    assertFalse(MaterializedViewIndexGenerationUtil.skipInitialSync(def1, def2));
    assertFalse(MaterializedViewIndexGenerationUtil.skipInitialSync(def2, def1));
  }
}
