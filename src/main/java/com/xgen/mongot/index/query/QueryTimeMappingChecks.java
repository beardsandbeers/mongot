package com.xgen.mongot.index.query;

import com.xgen.mongot.index.path.string.StringFieldPath;
import com.xgen.mongot.index.path.string.StringPath;
import com.xgen.mongot.util.FieldPath;
import java.util.Optional;

public interface QueryTimeMappingChecks {

  boolean indexedAsGeoPoint(FieldPath path, Optional<FieldPath> embeddedRoot);

  boolean indexedAsGeoShape(FieldPath path, Optional<FieldPath> embeddedRoot);

  void validatePathStringStorage(StringPath path, Optional<FieldPath> embeddedRoot)
      throws InvalidQueryException;

  void validateVectorField(FieldPath fieldPath, Optional<FieldPath> embeddedRoot, int dimensions)
      throws InvalidQueryException;

  boolean isStringFieldIndexed(StringFieldPath fieldPath, Optional<FieldPath> embeddedRoot);

  boolean isHighlightableStringField(StringPath path, Optional<FieldPath> embeddedRoot);

  boolean isIndexedAsEmbeddedDocumentsField(FieldPath fieldPath);

  boolean indexedAsDate(FieldPath path, Optional<FieldPath> embeddedRoot);

  boolean indexedAsNumber(FieldPath path, Optional<FieldPath> embeddedRoot);

  boolean indexedAsToken(FieldPath path, Optional<FieldPath> embeddedRoot);

  boolean indexedAsBoolean(FieldPath path, Optional<FieldPath> embeddedRoot);

  boolean indexedAsObjectId(FieldPath path, Optional<FieldPath> embeddedRoot);

  boolean indexedAsUuid(FieldPath path, Optional<FieldPath> embeddedRoot);

  boolean supportsFilter(); // 'true' for vector indexes, 'false' for search indexes

  /**
   * Checks whether the given field is indexed in a way that supports {@code $exists} queries.
   *
   * <p>For vector indexes this requires the field to be a {@code filter} field. For search indexes
   * the default returns {@code true} because any indexed field populates the field-names meta
   * field.
   */
  default boolean indexedForExists(FieldPath path, Optional<FieldPath> embeddedRoot) {
    return true;
  }
}
