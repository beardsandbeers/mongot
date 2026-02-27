package com.xgen.mongot.index.query;

import com.xgen.mongot.index.definition.VectorFieldDefinitionResolver;
import com.xgen.mongot.index.definition.VectorFieldSpecification;
import com.xgen.mongot.index.definition.VectorIndexFieldDefinition;
import com.xgen.mongot.index.path.string.StringFieldPath;
import com.xgen.mongot.index.path.string.StringPath;
import com.xgen.mongot.util.FieldPath;
import java.util.Optional;

/**
 * TODO(CLOUDP-280897): once we disallow running vector queries against search indexes, this class
 * should stop implementing the interface so we can remove all unsupported methods.
 */
public class VectorQueryTimeMappingChecks implements QueryTimeMappingChecks {

  private final VectorFieldDefinitionResolver resolver;

  public VectorQueryTimeMappingChecks(VectorFieldDefinitionResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  public void validateVectorField(FieldPath path, Optional<FieldPath> embeddedRoot, int dimensions)
      throws InvalidQueryException {
    Optional<VectorFieldSpecification> specification =
        this.resolver.getVectorFieldSpecification(path);

    if (specification.isEmpty()) {
      throw new InvalidQueryException(String.format("%s is not indexed as vector", path));
    }

    int numDimensions = specification.get().numDimensions();
    if (numDimensions != dimensions) {
      throw new InvalidQueryException(
          String.format(
              "vector field is indexed with %s dimensions but queried with %s",
              numDimensions, dimensions));
    }
  }

  @Override
  public boolean indexedAsDate(FieldPath path, Optional<FieldPath> embeddedRoot) {
    return isIndexedAsVectorFilter(path, embeddedRoot);
  }

  @Override
  public boolean indexedAsBoolean(FieldPath path, Optional<FieldPath> embeddedRoot) {
    return isIndexedAsVectorFilter(path, embeddedRoot);
  }

  @Override
  public boolean indexedAsToken(FieldPath path, Optional<FieldPath> embeddedRoot) {
    return isIndexedAsVectorFilter(path, embeddedRoot);
  }

  @Override
  public boolean indexedAsNumber(FieldPath path, Optional<FieldPath> embeddedRoot) {
    return isIndexedAsVectorFilter(path, embeddedRoot);
  }

  @Override
  public boolean indexedAsObjectId(FieldPath path, Optional<FieldPath> embeddedRoot) {
    return isIndexedAsVectorFilter(path, embeddedRoot);
  }

  @Override
  public boolean indexedAsUuid(FieldPath path, Optional<FieldPath> embeddedRoot) {
    return isIndexedAsVectorFilter(path, embeddedRoot);
  }

  @Override
  public void validatePathStringStorage(StringPath path, Optional<FieldPath> embeddedRoot) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean indexedAsGeoPoint(FieldPath path, Optional<FieldPath> embeddedRoot) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean indexedAsGeoShape(FieldPath path, Optional<FieldPath> embeddedRoot) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isStringFieldIndexed(StringFieldPath path, Optional<FieldPath> embeddedRoot) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isHighlightableStringField(StringPath path, Optional<FieldPath> embeddedRoot) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isIndexedAsEmbeddedDocumentsField(FieldPath fieldPath) {
    throw new UnsupportedOperationException();
  }

  private boolean isIndexedAsVectorFilter(FieldPath path, Optional<FieldPath> embeddedRoot) {
    return this.resolver.isIndexed(path, embeddedRoot, VectorIndexFieldDefinition.Type.FILTER);
  }

  @Override
  public boolean supportsFilter() {
    return true;
  }
}
