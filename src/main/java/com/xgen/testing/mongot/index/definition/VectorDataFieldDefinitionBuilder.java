package com.xgen.testing.mongot.index.definition;

import com.xgen.mongot.index.definition.VectorDataFieldDefinition;
import com.xgen.mongot.index.definition.VectorFieldSpecification;
import com.xgen.mongot.index.definition.VectorIndexingAlgorithm;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import com.xgen.mongot.util.Check;
import com.xgen.mongot.util.FieldPath;
import java.util.Optional;

public class VectorDataFieldDefinitionBuilder {
  Optional<Integer> numDimensions = Optional.empty();
  Optional<VectorIndexingAlgorithm> indexingAlgorithm =
      Optional.of(new VectorIndexingAlgorithm.HnswIndexingAlgorithm());
  Optional<VectorSimilarity> similarity = Optional.empty();
  Optional<VectorQuantization> quantization = Optional.empty();

  Optional<FieldPath> path = Optional.empty();

  public static VectorDataFieldDefinitionBuilder builder() {
    return new VectorDataFieldDefinitionBuilder();
  }

  public VectorDataFieldDefinitionBuilder numDimensions(int numDimensions) {
    this.numDimensions = Optional.of(numDimensions);
    return this;
  }

  public VectorDataFieldDefinitionBuilder similarity(VectorSimilarity similarity) {
    this.similarity = Optional.of(similarity);
    return this;
  }

  public VectorDataFieldDefinitionBuilder quantization(VectorQuantization quantization) {
    this.quantization = Optional.of(quantization);
    return this;
  }

  public VectorDataFieldDefinitionBuilder indexingAlgorithm(
      VectorIndexingAlgorithm indexingAlgorithm) {
    this.indexingAlgorithm = Optional.of(indexingAlgorithm);
    return this;
  }

  public VectorDataFieldDefinitionBuilder path(FieldPath path) {
    this.path = Optional.of(path);
    return this;
  }

  public VectorDataFieldDefinition build() {
    return new VectorDataFieldDefinition(
        Check.isPresent(this.path, "path"),
        new VectorFieldSpecification(
            Check.isPresent(this.numDimensions, "numDimensions"),
            Check.isPresent(this.similarity, "similarity"),
            Check.isPresent(this.quantization, "quantization"),
            Check.isPresent(this.indexingAlgorithm, "indexingMethod")));
  }
}
