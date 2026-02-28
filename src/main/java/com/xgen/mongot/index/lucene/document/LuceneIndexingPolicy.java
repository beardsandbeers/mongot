package com.xgen.mongot.index.lucene.document;

import com.xgen.mongot.index.lucene.document.builder.DocumentBlockBuilder;
import com.xgen.mongot.index.lucene.document.context.IndexingPolicyBuilderContext;

/**
 * Is able to instantiate {@link DocumentBlockBuilder}s given the {@code byte[] id} of a given
 * source document. Is the interface used externally to create a document block builder used to
 * create indexed documents for a new or updated document.
 */
public interface LuceneIndexingPolicy {
  DocumentBlockBuilder createBuilder(byte[] id);

  default DocumentBlockBuilder createBuilder(byte[] id, IndexingPolicyBuilderContext context) {
    return createBuilder(id);
  }
}
