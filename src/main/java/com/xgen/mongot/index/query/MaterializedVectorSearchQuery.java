package com.xgen.mongot.index.query;

import com.xgen.mongot.index.query.operators.ApproximateVectorSearchCriteria;
import com.xgen.mongot.index.query.operators.ExactVectorSearchCriteria;
import com.xgen.mongot.index.query.operators.VectorSearchCriteria;
import com.xgen.mongot.index.query.operators.VectorSearchFilter;
import com.xgen.mongot.index.query.operators.VectorSearchQueryInput;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.Vector;
import java.util.Optional;

/**
 * MaterializedVectorSearchQuery is a type safe wrapper of VectorSearchQuery to be used in
 * VectorIndexReader::query method. AutoEmbedding VectorSearchQuery doesn't have queryVector during
 * creation time, so it needs to be materialized to MaterializedVectorSearchQuery for
 * VectorIndexReader to consume.
 */
public record MaterializedVectorSearchQuery(VectorSearchQuery vectorSearchQuery, Vector vector) {
  public FieldPath path() {
    return this.vectorSearchQuery().criteria().path();
  }

  public Optional<Vector> queryVector() {
    return Optional.of(vector());
  }

  public Optional<VectorSearchQueryInput> query() {
    return vectorSearchQuery().criteria().query();
  }

  public Optional<VectorSearchFilter> filter() {
    return vectorSearchQuery().criteria().filter();
  }

  public int limit() {
    return vectorSearchQuery().criteria().limit();
  }

  public VectorSearchCriteria.Type getVectorSearchType() {
    return query()
        .map(ignored -> VectorSearchCriteria.Type.AUTO_EMBEDDING)
        .orElse(vectorSearchQuery().criteria().getVectorSearchType());
  }

  public Vector.VectorType queryVectorType() {
    return vector().getVectorType();
  }

  public boolean concurrent() {
    return vectorSearchQuery().concurrent();
  }

  public boolean returnStoredSource() {
    return vectorSearchQuery().returnStoredSource();
  }

  /**
   * Returns a materialized version of the query criteria, ensuring that the {@link
   * VectorSearchCriteria#queryVector()} is always present.
   */
  public VectorSearchCriteria materializedCriteria() {
    VectorSearchCriteria criteria = vectorSearchQuery().criteria();
    return switch (criteria) {
      case ApproximateVectorSearchCriteria approximate ->
          new ApproximateVectorSearchCriteria(
              approximate.path(),
              Optional.of(this.vector),
              approximate.query(),
              approximate.filter(),
              approximate.parentFilter(),
              approximate.limit(),
              approximate.numCandidates(),
              approximate.explainOptions(),
              approximate.returnStoredSource(),
              approximate.embeddedOptions());
      case ExactVectorSearchCriteria exact ->
          new ExactVectorSearchCriteria(
              exact.path(),
              Optional.of(this.vector),
              exact.query(),
              exact.filter(),
              exact.parentFilter(),
              exact.limit(),
              exact.returnStoredSource(),
              exact.embeddedOptions());
    };
  }
}
