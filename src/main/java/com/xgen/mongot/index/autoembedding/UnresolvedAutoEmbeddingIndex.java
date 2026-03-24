package com.xgen.mongot.index.autoembedding;

import com.xgen.mongot.index.Index;
import com.xgen.mongot.index.IndexUnavailableException;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.index.status.IndexStatus;
import java.io.IOException;

/**
 * This is an auto embedding index that failed to resolve due to missing sync source or materialized
 * view metadata. This index will be used to keep track of the index status for retry purposes.
 */
public class UnresolvedAutoEmbeddingIndex implements Index {

  private final IndexDefinition definition;
  // No need to make it AtomicReference, as only ConfigManager will update it.
  private IndexStatus status;

  public UnresolvedAutoEmbeddingIndex(IndexDefinition definition, IndexStatus status) {
    this.definition = definition;
    this.status = status;
  }

  @Override
  public IndexDefinition getDefinition() {
    return this.definition;
  }

  @Override
  public void setStatus(IndexStatus status) {
    this.status = status;
  }

  @Override
  public IndexStatus getStatus() {
    return this.status;
  }

  @Override
  public boolean isCompatibleWith(IndexDefinition indexDefinition) {
    return true;
  }

  @Override
  public void drop() throws IOException {}

  @Override
  public boolean isClosed() {
    // Always closed so that it won't be added to replication.
    return true;
  }

  @Override
  public void throwIfUnavailableForQuerying() throws IndexUnavailableException {}

  @Override
  public void close() throws IOException {}
}
