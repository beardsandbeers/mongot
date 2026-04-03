package com.xgen.mongot.index.definition.quantization;

import com.xgen.mongot.index.lucene.field.FieldName;

/** Supported vector field quantization types. */
public enum VectorQuantization {
  // no quantization
  NONE(FieldName.TypeField.KNN_VECTOR),

  // uses the Lucene float32 -> int7 quantization as the backend
  SCALAR(FieldName.TypeField.KNN_F32_Q7),

  // uses MongoDB's own float32 -> uint1 quantization as the backend
  BINARY(FieldName.TypeField.KNN_F32_Q1);

  VectorQuantization(FieldName.TypeField tf) {
    this.typeField = tf;
  }

  public FieldName.TypeField toTypeField() {
    return this.typeField;
  }

  private final FieldName.TypeField typeField;
}
