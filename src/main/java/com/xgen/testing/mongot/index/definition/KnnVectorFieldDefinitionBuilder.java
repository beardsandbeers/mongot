package com.xgen.testing.mongot.index.definition;

import com.xgen.mongot.index.definition.KnnVectorFieldDefinition;
import com.xgen.mongot.index.definition.VectorFieldSpecification;
import com.xgen.mongot.index.definition.VectorIndexingAlgorithm;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import com.xgen.mongot.util.Check;
import java.util.Optional;

public class KnnVectorFieldDefinitionBuilder {

  private Optional<Integer> dimensions = Optional.empty();
  private Optional<VectorSimilarity> similarity = Optional.empty();

  public static KnnVectorFieldDefinitionBuilder builder() {
    return new KnnVectorFieldDefinitionBuilder();
  }

  public KnnVectorFieldDefinitionBuilder dimensions(int dimensions) {
    this.dimensions = Optional.of(dimensions);
    return this;
  }

  public KnnVectorFieldDefinitionBuilder similarity(VectorSimilarity similarity) {
    this.similarity = Optional.of(similarity);
    return this;
  }

  public KnnVectorFieldDefinition build() {
    return new KnnVectorFieldDefinition(
        new VectorFieldSpecification(
            Check.isPresent(this.dimensions, "dimensions"),
            Check.isPresent(this.similarity, "similarity"),
            VectorQuantization.NONE,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm()));
  }
}
