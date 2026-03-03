package com.xgen.testing.mongot.index.query;

import com.xgen.mongot.index.query.operators.ExactVectorSearchCriteria;
import com.xgen.mongot.index.query.operators.VectorEmbeddedOptions;
import com.xgen.mongot.index.query.operators.VectorSearchFilter;
import com.xgen.mongot.index.query.operators.VectorSearchQueryInput;
import com.xgen.mongot.util.Check;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.Vector;
import java.util.Optional;

public class ExactVectorCriteriaBuilder {
  private Optional<FieldPath> path = Optional.empty();
  private Optional<Vector> queryVector = Optional.empty();
  private Optional<VectorSearchQueryInput> query = Optional.empty();
  private Optional<VectorSearchFilter> filter = Optional.empty();
  private Optional<VectorSearchFilter> parentFilter = Optional.empty();
  private Optional<Integer> limit = Optional.empty();
  private Optional<Boolean> returnStoredSource = Optional.empty();
  private Optional<VectorEmbeddedOptions> embeddedOptions = Optional.empty();

  public static ExactVectorCriteriaBuilder builder() {
    return new ExactVectorCriteriaBuilder();
  }

  public static ExactVectorCriteriaBuilder builder(ExactVectorSearchCriteria criteria) {
    ExactVectorCriteriaBuilder builder =
        new ExactVectorCriteriaBuilder()
            .path(criteria.path())
            .queryVector(criteria.queryVector().orElse(null))
            .limit(criteria.limit())
            .returnStoredSource(criteria.returnStoredSource());
    criteria.filter().ifPresent(builder::filter);
    criteria.query().ifPresent(builder::query);
    criteria.parentFilter().ifPresent(builder::parentFilter);
    criteria.embeddedOptions().ifPresent(builder::embeddedOptions);
    return builder;
  }

  public ExactVectorCriteriaBuilder path(FieldPath path) {
    this.path = Optional.of(path);
    return this;
  }

  public ExactVectorCriteriaBuilder path(String path) {
    return this.path(FieldPath.parse(path));
  }

  public ExactVectorCriteriaBuilder queryVector(Vector vector) {
    this.queryVector = Optional.ofNullable(vector);
    return this;
  }

  public ExactVectorCriteriaBuilder query(String query) {
    this.query = Optional.ofNullable(query).map(VectorSearchQueryInput.Text::new);
    return this;
  }

  public ExactVectorCriteriaBuilder query(VectorSearchQueryInput query) {
    this.query = Optional.ofNullable(query);
    return this;
  }

  public ExactVectorCriteriaBuilder filter(VectorSearchFilter filter) {
    this.filter = Optional.of(filter);
    return this;
  }

  public ExactVectorCriteriaBuilder parentFilter(VectorSearchFilter parentFilter) {
    this.parentFilter = Optional.of(parentFilter);
    return this;
  }

  public ExactVectorCriteriaBuilder limit(int limit) {
    this.limit = Optional.of(limit);
    return this;
  }

  public ExactVectorCriteriaBuilder returnStoredSource(Boolean returnStoredSource) {
    this.returnStoredSource = Optional.of(returnStoredSource);
    return this;
  }

  public ExactVectorCriteriaBuilder embeddedOptions(VectorEmbeddedOptions embeddedOptions) {
    this.embeddedOptions = Optional.of(embeddedOptions);
    return this;
  }

  public ExactVectorSearchCriteria build() {
    Check.isPresent(this.path, "path");
    Check.checkArg(
        this.queryVector.isPresent() != this.query.isPresent(),
        "one of queryVector and query must be present");
    //    Check.isPresent(this.filter, "filter");
    Check.isPresent(this.limit, "limit");

    return new ExactVectorSearchCriteria(
        this.path.get(),
        this.queryVector,
        this.query,
        this.filter,
        this.parentFilter,
        this.limit.get(),
        this.returnStoredSource.orElse(false),
        this.embeddedOptions);
  }
}
