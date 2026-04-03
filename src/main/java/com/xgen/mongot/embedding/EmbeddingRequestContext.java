package com.xgen.mongot.embedding;

import com.xgen.mongot.index.definition.quantization.VectorAutoEmbedQuantization;
import com.xgen.mongot.util.Check;

/**
 * Contextual information for an embedding request.
 *
 * <p>This includes metadata about the source of the embedding request, such as the database, index
 * name and collection name, which can be used for multi-tenant credential lookup, billing or
 * general logging.
 *
 * <p>{@link #outputDimension()} carries the resolved vector index {@code numDimensions} for
 * auto-embedding fields so providers (e.g. Voyage) can request a matching {@code output_dimension}.
 */
public record EmbeddingRequestContext(
    String database,
    String indexName,
    String collectionName,
    Integer outputDimension,
    VectorAutoEmbedQuantization autoEmbedQuantization) {

  public EmbeddingRequestContext {
    Check.argNotNull(database, "database");
    Check.argNotNull(indexName, "indexName");
    Check.argNotNull(collectionName, "collectionName");
    Check.argNotNull(outputDimension, "outputDimension");
    Check.checkArg(outputDimension > 0, "outputDimension must be positive");
    Check.argNotNull(autoEmbedQuantization, "autoEmbedQuantization");
  }
}
