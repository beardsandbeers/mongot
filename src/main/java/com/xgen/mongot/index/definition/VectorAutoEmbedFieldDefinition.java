package com.xgen.mongot.index.definition;

import static com.xgen.mongot.index.definition.VectorIndexFieldDefinition.Type.AUTO_EMBED;

import com.xgen.mongot.index.definition.quantization.VectorAutoEmbedQuantization;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import java.util.Objects;
import org.bson.BsonDocument;
import org.jetbrains.annotations.TestOnly;

/** Auto-embedding field definition with embedding model options. */
public class VectorAutoEmbedFieldDefinition extends VectorIndexVectorFieldDefinition {

  private final VectorAutoEmbedFieldSpecification specification;

  public VectorAutoEmbedFieldDefinition(
      FieldPath path, VectorAutoEmbedFieldSpecification specification) {
    super(path);
    this.specification = specification;
  }

  public VectorAutoEmbedFieldDefinition(
      String modelName,
      String modality,
      FieldPath autoEmbedPath,
      int numDimensions,
      VectorSimilarity similarity,
      VectorAutoEmbedQuantization autoEmbedQuantization,
      VectorIndexingAlgorithm indexingAlgorithm) {
    super(autoEmbedPath);
    this.specification =
        new VectorAutoEmbedFieldSpecification(
            numDimensions,
            similarity,
            autoEmbedQuantization,
            indexingAlgorithm,
            modelName,
            modality);
  }

  @TestOnly
  public VectorAutoEmbedFieldDefinition(
      String modelName,
      String modality,
      FieldPath autoEmbedPath,
      int numDimensions,
      VectorSimilarity similarity,
      VectorAutoEmbedQuantization autoEmbedQuantization) {
    super(autoEmbedPath);
    this.specification =
        new VectorAutoEmbedFieldSpecification(
            numDimensions,
            similarity,
            autoEmbedQuantization,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(),
            modelName,
            modality);
  }

  @TestOnly
  public VectorAutoEmbedFieldDefinition(String modelName, FieldPath textPath) {
    this(
        modelName,
        VectorAutoEmbedFieldSpecification.DEFAULT_MODALITY,
        textPath,
        1024,
        VectorSimilarity.DOT_PRODUCT,
        VectorAutoEmbedQuantization.FLOAT,
        new VectorIndexingAlgorithm.HnswIndexingAlgorithm());
  }

  @Override
  public VectorAutoEmbedFieldSpecification specification() {
    return this.specification;
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(VectorIndexFieldDefinition.Fields.TYPE, AUTO_EMBED)
        .field(VectorIndexFieldDefinition.Fields.PATH, this.path)
        .join(this.specification.toBson())
        .build();
  }

  public static VectorAutoEmbedFieldDefinition fromBson(DocumentParser parser)
      throws BsonParseException {
    FieldPath autoEmbedPath = parser.getField(VectorIndexFieldDefinition.Fields.PATH).unwrap();
    return new VectorAutoEmbedFieldDefinition(
        autoEmbedPath, VectorAutoEmbedFieldSpecification.fromBson(parser));
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
        && Objects.equals(this.specification, that.specification);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.path, this.specification);
  }
}
