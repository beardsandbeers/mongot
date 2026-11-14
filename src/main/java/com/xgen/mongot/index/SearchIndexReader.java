package com.xgen.mongot.index;

import com.xgen.mongot.cursor.batch.BatchSizeStrategy;
import com.xgen.mongot.cursor.batch.QueryCursorOptions;
import com.xgen.mongot.index.definition.TokenFieldDefinition;
import com.xgen.mongot.index.lucene.facet.TokenFacetsStateCache;
import com.xgen.mongot.index.query.InvalidQueryException;
import com.xgen.mongot.index.query.Query;
import com.xgen.mongot.index.query.QueryOptimizationFlags;
import com.xgen.mongot.index.query.SearchQuery;
import com.xgen.mongot.index.query.operators.Operator;
import com.xgen.mongot.util.FieldPath;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import org.apache.lucene.index.FieldInfos;

public interface SearchIndexReader extends IndexReader {

  /** Managed reader snapshots used for planning and executing one search on identical readers. */
  interface QuerySnapshot extends Closeable {
    default int partitions() {
      return 1;
    }

    void setCancellationCheck(Runnable check);

    TokenFieldDefinition tokenField(FieldPath path) throws InvalidQueryException;

    void collectKeys(Operator operator, FieldPath path, Consumer<String> keys, Runnable checkWork)
        throws IOException, InvalidQueryException;

    SearchProducerAndMetaResults query(
        Query query,
        QueryCursorOptions options,
        BatchSizeStrategy strategy,
        QueryOptimizationFlags flags)
        throws IOException, InvalidQueryException, InterruptedException;

    SearchProducerAndMetaProducer intermediateQuery(
        SearchQuery query,
        QueryCursorOptions options,
        BatchSizeStrategy strategy,
        QueryOptimizationFlags flags)
        throws IOException, InvalidQueryException, InterruptedException;
  }

  /** The caller owns the snapshot until query execution transfers it to batch producers. */
  default QuerySnapshot acquireQuerySnapshot(boolean concurrent)
      throws IOException, ReaderClosedException {
    throw new UnsupportedOperationException("This reader does not support pinned query planning");
  }

  class SearchProducerAndMetaResults {
    public final BatchProducer searchBatchProducer;
    public final MetaResults metaResults;

    public SearchProducerAndMetaResults(
        BatchProducer searchBatchProducer, MetaResults metaResults) {
      this.searchBatchProducer = searchBatchProducer;
      this.metaResults = metaResults;
    }
  }

  class SearchProducerAndMetaProducer {
    public final BatchProducer searchBatchProducer;
    public final BatchProducer metaBatchProducer;

    public SearchProducerAndMetaProducer(
        BatchProducer searchBatchProducer, BatchProducer metaBatchProducer) {
      this.searchBatchProducer = searchBatchProducer;
      this.metaBatchProducer = metaBatchProducer;
    }
  }

  /**
   * Returns a SearchBatchProducer that provides results that match the query, along with the
   * MetaResults for the query.
   */
  SearchProducerAndMetaResults query(
      Query query,
      QueryCursorOptions queryCursorOptions,
      BatchSizeStrategy batchSizeStrategy,
      QueryOptimizationFlags queryOptimizationFlags)
      throws IOException, InvalidQueryException, InterruptedException;

  /**
   * Returns a BatchProducer that provides results that match the query, along with a BatchProducer
   * that produces intermediate meta results for the query.
   */
  SearchProducerAndMetaProducer intermediateQuery(
      SearchQuery query,
      QueryCursorOptions queryCursorOptions,
      BatchSizeStrategy batchSizeStrategy,
      QueryOptimizationFlags queryOptimizationFlags)
      throws IOException, InvalidQueryException, InterruptedException;

  long getNumEmbeddedRootDocuments() throws IOException, ReaderClosedException;

  List<FieldInfos> getFieldInfos() throws IOException, ReaderClosedException;

  /**
   * Point-in-time view of this reader's token-facets ordinal-map cache. Readers without a cache
   * return {@link TokenFacetsStateCache.Snapshot#EMPTY}.
   */
  TokenFacetsStateCache.Snapshot getTokenFacetsStateCacheSnapshot();
}
