package com.xgen.mongot.index;

import com.google.common.annotations.VisibleForTesting;
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
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.lucene.index.FieldInfos;

public class MeteredSearchIndexReader implements SearchIndexReader {

  @Override
  public com.xgen.mongot.index.statistics.LuceneStatisticsSnapshot acquireStatisticsSnapshot()
      throws IOException, ReaderClosedException {
    return this.indexReader.acquireStatisticsSnapshot();
  }

  private final SearchIndexReader indexReader;
  private final IndexMetricsUpdater.QueryingMetricsUpdater queryingMetricsUpdater;
  private final QueryMetricsRecorder queryMetricsRecorder;
  private final BooleanSupplier recordTotalFacetBucketsMetric;

  public MeteredSearchIndexReader(
      SearchIndexReader indexReader,
      IndexMetricsUpdater.QueryingMetricsUpdater queryingMetricsUpdater,
      BooleanSupplier recordTotalFacetBucketsMetric) {
    this.indexReader = indexReader;
    this.queryingMetricsUpdater = queryingMetricsUpdater;
    this.recordTotalFacetBucketsMetric = recordTotalFacetBucketsMetric;
    this.queryMetricsRecorder =
        new QueryMetricsRecorder(
            queryingMetricsUpdater.getQueryFeaturesMetricsUpdater(),
            queryingMetricsUpdater.getSearchAggregationMetricsReporter());
  }

  @Override
  public SearchProducerAndMetaResults query(
      Query query,
      QueryCursorOptions queryCursorOptions,
      BatchSizeStrategy batchSizeStrategy,
      QueryOptimizationFlags queryOptimizationFlags)
      throws IOException, InvalidQueryException, InterruptedException {
    return query(
        query, queryCursorOptions, batchSizeStrategy, queryOptimizationFlags, Optional.empty());
  }

  private SearchProducerAndMetaResults query(
      Query query,
      QueryCursorOptions queryCursorOptions,
      BatchSizeStrategy batchSizeStrategy,
      QueryOptimizationFlags queryOptimizationFlags,
      Optional<QuerySnapshot> snapshot)
      throws IOException, InvalidQueryException, InterruptedException {
    this.queryingMetricsUpdater.getTotalQueryCounter().increment();
    try {
      SearchProducerAndMetaResults result =
          snapshot.isPresent()
              ? snapshot
                  .get()
                  .query(query, queryCursorOptions, batchSizeStrategy, queryOptimizationFlags)
              : this.indexReader.query(
                  query, queryCursorOptions, batchSizeStrategy, queryOptimizationFlags);
      this.queryingMetricsUpdater.recordTotalFacetBucketsIfApplicable(
          query, this.recordTotalFacetBucketsMetric);
      this.queryMetricsRecorder.record(query, queryCursorOptions);
      return result;
    } catch (Exception e) {
      this.queryingMetricsUpdater.handleQueryException(
          e, "from SearchIndexReader.query, shape=" + QueryShape.of(query));
      throw e;
    }
  }

  @Override
  public SearchProducerAndMetaProducer intermediateQuery(
      SearchQuery query,
      QueryCursorOptions queryCursorOptions,
      BatchSizeStrategy batchSizeStrategy,
      QueryOptimizationFlags queryOptimizationFlags)
      throws IOException, InvalidQueryException, InterruptedException {
    return intermediateQuery(
        query, queryCursorOptions, batchSizeStrategy, queryOptimizationFlags, Optional.empty());
  }

  private SearchProducerAndMetaProducer intermediateQuery(
      SearchQuery query,
      QueryCursorOptions queryCursorOptions,
      BatchSizeStrategy batchSizeStrategy,
      QueryOptimizationFlags queryOptimizationFlags,
      Optional<QuerySnapshot> snapshot)
      throws IOException, InvalidQueryException, InterruptedException {
    this.queryingMetricsUpdater.getTotalQueryCounter().increment();
    try {
      SearchProducerAndMetaProducer result =
          snapshot.isPresent()
              ? snapshot
                  .get()
                  .intermediateQuery(
                      query, queryCursorOptions, batchSizeStrategy, queryOptimizationFlags)
              : this.indexReader.intermediateQuery(
                  query, queryCursorOptions, batchSizeStrategy, queryOptimizationFlags);
      this.queryingMetricsUpdater.recordTotalFacetBucketsIfApplicable(
          query, this.recordTotalFacetBucketsMetric);
      this.queryMetricsRecorder.record(query, queryCursorOptions);
      return result;
    } catch (Exception e) {
      this.queryingMetricsUpdater.handleQueryException(
          e, "from SearchIndexReader.intermediateQuery, shape=" + QueryShape.of(query));
      throw e;
    }
  }

  @Override
  public long getNumEmbeddedRootDocuments() throws IOException, ReaderClosedException {
    return this.indexReader.getNumEmbeddedRootDocuments();
  }

  @Override
  public List<FieldInfos> getFieldInfos() throws IOException, ReaderClosedException {
    return this.indexReader.getFieldInfos();
  }

  @Override
  public TokenFacetsStateCache.Snapshot getTokenFacetsStateCacheSnapshot() {
    return this.indexReader.getTokenFacetsStateCacheSnapshot();
  }

  @Override
  public void refresh() throws IOException, ReaderClosedException {
    this.indexReader.refresh();
  }

  @Override
  public QuerySnapshot acquireQuerySnapshot(boolean concurrent)
      throws IOException, ReaderClosedException {
    return new MeteredQuerySnapshot(this.indexReader.acquireQuerySnapshot(concurrent));
  }

  private final class MeteredQuerySnapshot implements QuerySnapshot {
    private final QuerySnapshot snapshot;

    private MeteredQuerySnapshot(QuerySnapshot snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public int partitions() {
      return this.snapshot.partitions();
    }

    @Override
    public void setCancellationCheck(Runnable check) {
      this.snapshot.setCancellationCheck(check);
    }

    @Override
    public TokenFieldDefinition tokenField(FieldPath path) throws InvalidQueryException {
      return this.snapshot.tokenField(path);
    }

    @Override
    public void collectKeys(
        Operator operator, FieldPath path, Consumer<String> keys, Runnable checkWork)
        throws IOException, InvalidQueryException {
      this.snapshot.collectKeys(operator, path, keys, checkWork);
    }

    @Override
    public SearchProducerAndMetaResults query(
        Query query,
        QueryCursorOptions options,
        BatchSizeStrategy strategy,
        QueryOptimizationFlags flags)
        throws IOException, InvalidQueryException, InterruptedException {
      return MeteredSearchIndexReader.this.query(
          query, options, strategy, flags, Optional.of(this.snapshot));
    }

    @Override
    public SearchProducerAndMetaProducer intermediateQuery(
        SearchQuery query,
        QueryCursorOptions options,
        BatchSizeStrategy strategy,
        QueryOptimizationFlags flags)
        throws IOException, InvalidQueryException, InterruptedException {
      return MeteredSearchIndexReader.this.intermediateQuery(
          query, options, strategy, flags, Optional.of(this.snapshot));
    }

    @Override
    public void close() throws IOException {
      this.snapshot.close();
    }
  }

  @Override
  public void close() {
    this.indexReader.close();
  }

  @Override
  public void open() {
    this.indexReader.open();
  }

  @VisibleForTesting
  public SearchIndexReader unwrap() {
    return this.indexReader;
  }
}
