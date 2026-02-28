package com.xgen.mongot.index.lucene.backing;

import com.google.common.collect.ImmutableList;
import com.xgen.mongot.index.status.IndexStatus;
import java.io.Closeable;
import java.io.IOException;
import java.util.function.Supplier;
import org.apache.lucene.search.ReferenceManager;

/** Strategy for managing index-level resources: refresh, storage metrics, and cleanup. */
public interface IndexBackingStrategy {
  Closeable createIndexRefresher(
      Supplier<IndexStatus> statusRef, ImmutableList<ReferenceManager<?>> searcherManagers);

  void releaseResources() throws IOException;

  long getIndexSize();

  long getLargestIndexFileSize();

  long getNumFilesInIndex();

  long getIndexSizeForIndexPartition(int indexPartitionId);

  /** Clear metadata associated with the index (this does not release all the index resources). */
  void clearMetadata() throws IOException;
}
