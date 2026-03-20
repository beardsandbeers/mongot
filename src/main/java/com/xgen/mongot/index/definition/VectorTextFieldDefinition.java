package com.xgen.mongot.index.definition;

import static com.xgen.mongot.embedding.providers.configs.EmbeddingModelConfig.DEFAULT_EMBEDDING_MODEL_CONFIG;

import com.google.common.annotations.VisibleForTesting;
import com.xgen.mongot.embedding.providers.configs.EmbeddingModelCatalog;
import com.xgen.mongot.embedding.providers.configs.EmbeddingModelConfig;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import java.util.Objects;
import org.bson.BsonDocument;

/** Part of a Vector Index definition that represents a text field to be embedded. */
public class VectorTextFieldDefinition extends VectorIndexVectorFieldDefinition {
  private static final VectorSimilarity DEFAULT_VECTOR_SIMILARITY = VectorSimilarity.EUCLIDEAN;
  private static final VectorQuantization DEFAULT_VECTOR_QUANTIZATION = VectorQuantization.NONE;

  private final VectorTextFieldSpecification specification;

  static class Fields {
    static final Field.WithDefault<String> MODEL =
        Field.builder("model")
            .stringField()
            .optional()
            // TODO(CLOUDP-321187): Update to using default model config from MMS conf-call
            .withDefault(DEFAULT_EMBEDDING_MODEL_CONFIG.name());

    private static final Field.WithDefault<VectorSimilarity> AUTO_EMBEDDING_SIMILARITY =
        Field.builder("similarity")
            .enumField(VectorSimilarity.class)
            .asCamelCase()
            .optional()
            // TODO(CLOUDP-337537): Change to DOT_PRODUCT once migration is done.
            .withDefault(DEFAULT_VECTOR_SIMILARITY);
  }

  @VisibleForTesting
  public VectorTextFieldDefinition(FieldPath textPath) {
    this("voyage-3-large", textPath, DEFAULT_VECTOR_SIMILARITY);
  }

  public VectorTextFieldDefinition(String modelName, FieldPath textPath) {
    this(modelName, textPath, DEFAULT_VECTOR_SIMILARITY);
  }

  public VectorTextFieldDefinition(
      String modelName, FieldPath textPath, VectorSimilarity similarity) {
    this(modelName, textPath, similarity, DEFAULT_VECTOR_QUANTIZATION);
  }

  public VectorTextFieldDefinition(
      String modelName,
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
            modelName);
  }

  @Override
  public VectorTextFieldSpecification specification() {
    return this.specification;
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(VectorIndexFieldDefinition.Fields.TYPE, Type.TEXT)
        .field(VectorIndexFieldDefinition.Fields.PATH, this.path)
        .field(Fields.MODEL, this.specification.modelName().toLowerCase())
        .fieldOmitDefaultValue(Fields.AUTO_EMBEDDING_SIMILARITY, this.specification.similarity())
        .fieldOmitDefaultValue(
            VectorFieldSpecification.Fields.QUANTIZATION, this.specification.quantization())
        .build();
  }

  public static VectorTextFieldDefinition fromBson(DocumentParser parser)
      throws BsonParseException {
    FieldPath textPath = parser.getField(VectorIndexFieldDefinition.Fields.PATH).unwrap();
    // TODO(CLOUDP-321187): Place index definition json validation logic in MMS as a fallback
    String modelName = parser.getField(Fields.MODEL).unwrap().toLowerCase();
    VectorSimilarity similarity = parser.getField(Fields.AUTO_EMBEDDING_SIMILARITY).unwrap();
    VectorQuantization quantization =
        parser.getField(VectorFieldSpecification.Fields.QUANTIZATION).unwrap();
    return new VectorTextFieldDefinition(modelName, textPath, similarity, quantization);
  }

  private static EmbeddingModelConfig validateAndGet(String modelName) {
    return EmbeddingModelCatalog.isModelRegistered(modelName)
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
    return Type.TEXT;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VectorTextFieldDefinition that)) {
      return false;
    }
    return Objects.equals(this.path, that.path)
        && Objects.equals(this.getType(), that.getType())
        && Objects.equals(this.specification.modelName(), that.specification.modelName())
        // TODO(CLOUDP-337537): Remove this once migration is done.
        && Objects.equals(this.specification.similarity(), that.specification.similarity())
        && Objects.equals(this.specification.quantization(), that.specification.quantization());
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        this.path,
        this.specification.modelName(),
        this.specification.similarity(),
        this.specification.quantization());
  }
}
