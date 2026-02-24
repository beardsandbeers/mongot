package com.xgen.mongot.index.definition;

import com.xgen.mongot.util.Check;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentEncodable;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import com.xgen.mongot.util.bson.parser.FieldPathField;
import java.util.EnumSet;

/** Abstract parent class representing various configuration options for Vector Indexes. */
public abstract class VectorIndexFieldDefinition implements DocumentEncodable {

  public enum Type {
    VECTOR,
    FILTER,
    TEXT,
    EMBEDDED_DOCUMENTS,
    AUTO_EMBED
  }

  final FieldPath path;

  public VectorIndexFieldDefinition(FieldPath path) {
    this.path = path;
  }

  static class Fields {
    static final Field.Required<Type> TYPE =
        Field.builder("type").enumField(Type.class).asCamelCase().required();
    static final Field.Required<FieldPath> PATH =
        Field.builder("path").classField(FieldPathField::parse, FieldPathField::encode).required();
  }

  public static VectorIndexFieldDefinition fromBson(DocumentParser parser)
      throws BsonParseException {
    return switch (parser.getField(Fields.TYPE).unwrap()) {
      case VECTOR -> VectorDataFieldDefinition.fromBson(parser);
      case FILTER -> VectorIndexFilterFieldDefinition.fromBson(parser);
      case TEXT -> VectorTextFieldDefinition.fromBson(parser);
      case EMBEDDED_DOCUMENTS -> VectorEmbeddedDocumentsFieldDefinition.fromBson(parser);
      case AUTO_EMBED -> VectorAutoEmbedFieldDefinition.fromBson(parser);
    };
  }

  public abstract Type getType();

  @Override
  public abstract boolean equals(Object o);

  @Override
  public abstract int hashCode();

  public VectorIndexVectorFieldDefinition asVectorField() {
    Check.expectedType(EnumSet.of(Type.VECTOR, Type.TEXT, Type.AUTO_EMBED), this.getType());
    return (VectorIndexVectorFieldDefinition) this;
  }

  public VectorIndexFilterFieldDefinition asFilterField() {
    Check.expectedType(Type.FILTER, this.getType());
    return (VectorIndexFilterFieldDefinition) this;
  }

  public VectorTextFieldDefinition asVectorTextField() {
    Check.expectedType(Type.TEXT, this.getType());
    return (VectorTextFieldDefinition) this;
  }

  public VectorAutoEmbedFieldDefinition asVectorAutoEmbedField() {
    Check.expectedType(Type.AUTO_EMBED, this.getType());
    return (VectorAutoEmbedFieldDefinition) this;
  }

  VectorIndexFieldDefinition throwDuplicateException(VectorIndexFieldDefinition other) {
    // should never occur since we validate for duplicate paths before creating field definition
    throw new IllegalArgumentException(
        "same path specified for separate field definitions on the same index");
  }

  public FieldPath getPath() {
    return this.path;
  }

  /** Returns true if it is an instance of VectorIndexVectorFieldDefinition. */
  public boolean isVectorField() {
    return this instanceof VectorIndexVectorFieldDefinition;
  }

  /** Returns true if this field is an auto-embedding field (TEXT or AUTO_EMBED). */
  public boolean isAutoEmbedField() {
    Type type = getType();
    return type == Type.AUTO_EMBED || type == Type.TEXT;
  }
}
