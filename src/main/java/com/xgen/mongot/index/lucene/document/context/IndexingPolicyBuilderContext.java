package com.xgen.mongot.index.lucene.document.context;

import com.google.common.collect.ImmutableMap;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.Vector;

/**
 * Context passed to {@link
 * com.xgen.mongot.index.lucene.document.LuceneIndexingPolicy#createBuilder(byte[],
 * IndexingPolicyBuilderContext)} bundling per-document parameters needed for builder creation.
 */
public final class IndexingPolicyBuilderContext {

  private final ImmutableMap<FieldPath, ImmutableMap<String, Vector>> autoEmbeddings;

  private IndexingPolicyBuilderContext(Builder builder) {
    this.autoEmbeddings = builder.autoEmbeddings;
  }

  public ImmutableMap<FieldPath, ImmutableMap<String, Vector>> autoEmbeddings() {
    return this.autoEmbeddings;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ImmutableMap<FieldPath, ImmutableMap<String, Vector>> autoEmbeddings =
        ImmutableMap.of();

    private Builder() {}

    public Builder autoEmbeddings(
        ImmutableMap<FieldPath, ImmutableMap<String, Vector>> autoEmbeddings) {
      this.autoEmbeddings = autoEmbeddings;
      return this;
    }

    public IndexingPolicyBuilderContext build() {
      return new IndexingPolicyBuilderContext(this);
    }
  }
}
