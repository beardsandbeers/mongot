package com.xgen.mongot.index.definition;

import com.xgen.mongot.index.definition.quantization.VectorAutoEmbedQuantization;

public sealed interface VectorFieldAutoEmbeddingSpecification
    permits VectorTextFieldSpecification, VectorAutoEmbedFieldSpecification {
  String modelName();

  VectorAutoEmbedQuantization autoEmbedQuantization();

  int numDimensions();
}
