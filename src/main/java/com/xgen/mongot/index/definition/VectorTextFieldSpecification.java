package com.xgen.mongot.index.definition;

import com.xgen.mongot.index.definition.quantization.VectorAutoEmbedQuantization;
import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import java.util.Objects;

/**
 * A vector field specification for text fields that require embedding model information.
 *
 * <p>Extends {@link VectorFieldSpecification} to include the name of the embedding model used to
 * generate vectors from text. This is used for auto-embedding features where text queries need to
 * be converted to vectors.
 */
public final class VectorTextFieldSpecification extends VectorFieldSpecification
    implements VectorFieldAutoEmbeddingSpecification {
  private final String modelName;

  public VectorTextFieldSpecification(
      int numDimensions,
      VectorSimilarity similarity,
      VectorQuantization quantization,
      VectorIndexingAlgorithm indexingAlgorithm,
      String modelName) {
    super(numDimensions, similarity, quantization, indexingAlgorithm);
    this.modelName = modelName;
  }

  /**
   * Returns the name of the embedding model used to generate vectors from text.
   *
   * @return the embedding model name
   */
  public String modelName() {
    return this.modelName;
  }

  @Override
  public VectorAutoEmbedQuantization autoEmbedQuantization() {
    return VectorAutoEmbedQuantization.FLOAT;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    VectorTextFieldSpecification that = (VectorTextFieldSpecification) o;
    return Objects.equals(this.modelName, that.modelName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), this.modelName);
  }
}
