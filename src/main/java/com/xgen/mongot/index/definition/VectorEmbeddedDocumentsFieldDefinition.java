package com.xgen.mongot.index.definition;

import com.google.common.collect.ImmutableMap;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import org.bson.BsonDocument;

/**
 * Represents an embedded documents field definition within a Vector Index.
 *
 * <p>This allows Vector Indexes to define filter fields within array subdocuments, enabling
 * filtering on nested document structures.
 *
 * <p>Example usage:
 *
 * <pre>
 * {
 *   "type": "embeddedDocuments",
 *   "path": "sections",
 *   "fields": {
 *     "category": { "type": "filter" },
 *     "priority": { "type": "filter" }
 *   }
 * }
 * </pre>
 */
public class VectorEmbeddedDocumentsFieldDefinition extends VectorIndexFieldDefinition {

  private final ImmutableMap<String, VectorIndexFieldDefinition> fields;

  public static class Fields {
    static final Field.WithDefault<Map<String, VectorIndexFieldDefinition>> FIELDS =
        Field.builder("fields")
            .classField(VectorIndexFieldDefinition::fromBson, VectorIndexFieldDefinition::toBson)
            .disallowUnknownFields()
            .asMap()
            .optional()
            .withDefault(Collections.emptyMap());
  }

  public VectorEmbeddedDocumentsFieldDefinition(
      FieldPath path, Map<String, VectorIndexFieldDefinition> fields) {
    super(path);
    this.fields = ImmutableMap.copyOf(fields);
  }

  /**
   * Create a VectorEmbeddedDocumentsFieldDefinition for an embedded vector root.
   *
   * <p>This method extracts all vector fields that are children of the given embedded root path and
   * creates a field definition for them.
   *
   * @param embeddedRootPath the path to the embedded root (e.g., "sections")
   * @param mapping the vector index field mapping containing all field definitions
   * @return a new VectorEmbeddedDocumentsFieldDefinition for the embedded root
   */
  public static VectorEmbeddedDocumentsFieldDefinition create(
      FieldPath embeddedRootPath, VectorIndexFieldMapping mapping) {
    // For now, we create an empty fields map since vector fields are handled separately
    // The embedded document builder will handle the vector fields based on the mapping
    return new VectorEmbeddedDocumentsFieldDefinition(embeddedRootPath, Collections.emptyMap());
  }

  @Override
  public Type getType() {
    return Type.EMBEDDED_DOCUMENTS;
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(VectorIndexFieldDefinition.Fields.TYPE, getType())
        .field(VectorIndexFieldDefinition.Fields.PATH, this.path)
        .field(Fields.FIELDS, this.fields)
        .build();
  }

  public static VectorEmbeddedDocumentsFieldDefinition fromBson(DocumentParser parser)
      throws BsonParseException {
    FieldPath path = parser.getField(VectorIndexFieldDefinition.Fields.PATH).unwrap();
    Map<String, VectorIndexFieldDefinition> fields = parser.getField(Fields.FIELDS).unwrap();
    return new VectorEmbeddedDocumentsFieldDefinition(path, fields);
  }

  public ImmutableMap<String, VectorIndexFieldDefinition> fields() {
    return this.fields;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VectorEmbeddedDocumentsFieldDefinition that)) {
      return false;
    }
    return Objects.equals(this.path, that.path) && Objects.equals(this.fields, that.fields);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.path, this.fields);
  }
}
