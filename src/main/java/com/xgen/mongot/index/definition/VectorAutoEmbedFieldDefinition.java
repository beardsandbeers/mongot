package com.xgen.mongot.index.definition;

import static com.xgen.mongot.embedding.providers.configs.EmbeddingModelConfig.DEFAULT_EMBEDDING_MODEL_CONFIG;
import static com.xgen.mongot.index.definition.VectorIndexFieldDefinition.Type.AUTO_EMBED;
import static com.xgen.mongot.index.definition.VectorTextFieldSpecification.DEFAULT_MODALITY;

import com.xgen.mongot.embedding.providers.configs.EmbeddingModelCatalog;
import com.xgen.mongot.embedding.providers.configs.EmbeddingModelConfig;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import java.util.Objects;
import org.bson.BsonDocument;
import org.jetbrains.annotations.TestOnly;

/**
 * Part of auto embedding Vector Index definition that represents a field with embedding model
 * options.
 */
public class VectorAutoEmbedFieldDefinition extends VectorIndexVectorFieldDefinition {
  private static final VectorSimilarity DEFAULT_VECTOR_SIMILARITY = VectorSimilarity.DOT_PRODUCT;
  private static final VectorQuantization DEFAULT_VECTOR_QUANTIZATION = VectorQuantization.NONE;

  private static class Fields {
    // TODO(CLOUDP-355237): Add modelOption field and more
    static final Field.Required<String> MODEL = Field.builder("model").stringField().required();
    static final Field.Required<String> MODALITY =
        Field.builder("modality")
            .stringField()
            .validate(
                modality ->
                    modality.equalsIgnoreCase(DEFAULT_MODALITY)
                        ? java.util.Optional.empty()
                        : java.util.Optional.of("must be '" + DEFAULT_MODALITY + "'"))
            .required();
  }

  private final VectorTextFieldSpecification specification;

  public VectorAutoEmbedFieldDefinition(
      String modelName,
      String modality,
      FieldPath textPath,
      VectorSimilarity similarity,
      VectorQuantization quantization) {
    super(textPath);
    this.specification =
        new VectorTextFieldSpecification(
            validateAndGet(modelName).collectionScan().modelConfig().getOutputDimensions(),
            similarity,
            quantization,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(),
            modelName,
            modality);
  }

  public VectorAutoEmbedFieldDefinition(String modelName, String modality, FieldPath textPath) {
    this(modelName, modality, textPath, DEFAULT_VECTOR_SIMILARITY, DEFAULT_VECTOR_QUANTIZATION);
  }

  public VectorAutoEmbedFieldDefinition(String modelName, FieldPath textPath) {
    this(
        modelName,
        DEFAULT_MODALITY,
        textPath,
        DEFAULT_VECTOR_SIMILARITY,
        DEFAULT_VECTOR_QUANTIZATION);
  }

  @TestOnly
  public VectorAutoEmbedFieldDefinition(FieldPath textPath) {
    this("voyage-3-large", DEFAULT_MODALITY, textPath);
  }

  @Override
  public VectorTextFieldSpecification specification() {
    return this.specification;
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(VectorIndexFieldDefinition.Fields.TYPE, AUTO_EMBED)
        .field(VectorIndexFieldDefinition.Fields.PATH, this.path)
        .field(Fields.MODEL, this.specification.modelName().toLowerCase())
        .field(Fields.MODALITY, this.specification.modality().toLowerCase())
        .build();
  }

  public static VectorAutoEmbedFieldDefinition fromBson(DocumentParser parser)
      throws BsonParseException {
    FieldPath textPath = parser.getField(VectorIndexFieldDefinition.Fields.PATH).unwrap();
    String modelName = parser.getField(Fields.MODEL).unwrap().toLowerCase();
    String modality = parser.getField(Fields.MODALITY).unwrap().toLowerCase();
    return new VectorAutoEmbedFieldDefinition(
        modelName, modality, textPath, DEFAULT_VECTOR_SIMILARITY, DEFAULT_VECTOR_QUANTIZATION);
  }

  private static EmbeddingModelConfig validateAndGet(String modelName) {
    return EmbeddingModelCatalog.isModelRegistered(modelName)
            && EmbeddingModelCatalog.isMatViewEnabled()
        ? EmbeddingModelCatalog.getModelConfig(modelName)
        : new EmbeddingModelConfig(
            modelName,
            DEFAULT_EMBEDDING_MODEL_CONFIG.provider(),
            DEFAULT_EMBEDDING_MODEL_CONFIG.useFlexTier(),
            DEFAULT_EMBEDDING_MODEL_CONFIG.query(),
            DEFAULT_EMBEDDING_MODEL_CONFIG.changeStream(),
            DEFAULT_EMBEDDING_MODEL_CONFIG.collectionScan());
  }

  @Override
  public Type getType() {
    return AUTO_EMBED;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VectorAutoEmbedFieldDefinition that)) {
      return false;
    }
    return Objects.equals(this.path, that.path)
        && Objects.equals(this.specification.modelName(), that.specification.modelName())
        && Objects.equals(this.specification.modality(), that.specification.modality());
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.path, this.specification.modelName() + this.specification.modality());
  }
}
