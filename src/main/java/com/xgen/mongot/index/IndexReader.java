package com.xgen.mongot.index;

import com.xgen.mongot.index.statistics.LuceneStatisticsSnapshot;
import java.io.IOException;

/** An IndexReader is capable of reading an index and producing results for queries. */
public interface IndexReader {

  /**
   * Retains the current Lucene readers for bounded statistics work. Caller must close the lease.
   */
  default LuceneStatisticsSnapshot acquireStatisticsSnapshot()
      throws IOException, ReaderClosedException {
    throw new UnsupportedOperationException("This reader does not expose Lucene statistics");
  }

  /**
   * Refreshes the IndexReader such that future calls to query() should see all updates to the Index
   * that occurred prior to the refresh() call.
   */
  void refresh() throws IOException, ReaderClosedException;

  void open();

  void close();
}
