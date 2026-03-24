package com.xgen.mongot.index.autoembedding;

import static com.xgen.mongot.index.status.IndexStatus.Reason.AUTO_EMBEDDING_RESOLUTION_FAILED;
import static com.xgen.mongot.index.status.IndexStatus.Reason.AUTO_EMBEDDING_RESOLUTION_RETRY;

import com.xgen.mongot.embedding.exceptions.MaterializedViewTransientException;
import com.xgen.mongot.index.IndexGeneration;
import com.xgen.mongot.index.definition.IndexDefinitionGeneration;
import com.xgen.mongot.index.status.IndexStatus;

public class UnresolvedAutoEmbeddingIndexGeneration extends IndexGeneration {
  private UnresolvedAutoEmbeddingIndexGeneration(
      UnresolvedAutoEmbeddingIndex index, IndexDefinitionGeneration definitionGeneration) {
    super(index, definitionGeneration);
  }

  public static UnresolvedAutoEmbeddingIndexGeneration create(
      IndexDefinitionGeneration definitionGeneration,
      MaterializedViewTransientException.Reason reason) {
    IndexStatus failedStatus =
        switch (reason) {
          case MONGO_CLIENT_NOT_AVAILABLE ->
              IndexStatus.failed(
                  "Unable to create materialized view index, "
                      + "will retry when sync source is updated.",
                  AUTO_EMBEDDING_RESOLUTION_FAILED);
          default ->
              IndexStatus.failed(
                  "Unable to create materialized view index, will retry in next update cycle.",
                  AUTO_EMBEDDING_RESOLUTION_RETRY);
        };
    var failedIndex =
        new UnresolvedAutoEmbeddingIndex(definitionGeneration.getIndexDefinition(), failedStatus);
    return new UnresolvedAutoEmbeddingIndexGeneration(failedIndex, definitionGeneration);
  }
}
