package com.xgen.mongot.index.lucene.searcher;

import com.xgen.mongot.metrics.MetricsFactory;
import com.xgen.mongot.metrics.ServerStatusDataExtractor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.apache.lucene.search.LRUQueryCache;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryCache;

/**
 * Provides a {@link QueryCache} instance used to cache filter and no-score queries and improve
 * performance of repeated query executions.
 */
public interface QueryCacheProvider {
  Optional<QueryCache> queryCache();

  /**
   * Default {@link QueryCacheProvider} implementation that delegates to Lucene’s built-in default
   * {@link QueryCache}.
   *
   * <p>This provider simply returns the shared query cache instance used by {@link
   * LuceneIndexSearcher}, without any additional instrumentation or customization. Both this
   * provider instance and the underlying {@link QueryCache} are <b>safe</b> to reuse across
   * multiple Lucene IndexSearcher instances and across multiple indices.
   */
  class DefaultQueryCacheProvider implements QueryCacheProvider {

    @Override
    public Optional<QueryCache> queryCache() {
      return Optional.ofNullable(LuceneIndexSearcher.getDefaultQueryCache());
    }
  }

  /**
   * {@link QueryCacheProvider} implementation that provides a metered {@link LRUQueryCache}-based
   * cache identical in configuration to Lucene 9.11's default query cache, but augmented with
   * metric instrumentation.
   *
   * <ul>
   *   <li>Maximum number of cached queries: 1000
   *   <li>Maximum memory usage: the smaller of 32 MB or 5% of the JVM maximum heap size
   * </ul>
   *
   * <p>The provider creates exactly one {@link QueryCache} instance, which is stored internally and
   * returned for all consumers. Both this provider instance and the underlying {@link QueryCache}
   * are <b>safe</b> to reuse across multiple Lucene IndexSearcher instances and across multiple
   * indices.
   */
  class MeteredQueryCacheProvider implements QueryCacheProvider {
    private final MetricsFactory metricsFactory;
    private final QueryCache queryCache;

    public MeteredQueryCacheProvider(MeterRegistry meterRegistry) {
      this.metricsFactory =
          new MetricsFactory(
              "luceneQueryCache", meterRegistry, ServerStatusDataExtractor.Scope.LUCENE.getTag());
      this.queryCache = initCache();
    }

    // Cache configuration is identical to the default one in Lucene 9.11
    private QueryCache initCache() {
      int maxCachedQueries = 1000;
      // min of 32MB or 5% of the heap size
      long maxRamBytesUsed = Math.min(1L << 25, Runtime.getRuntime().maxMemory() / 20);
      return InstrumentingLruQueryCache.create(
          this.metricsFactory, maxCachedQueries, maxRamBytesUsed);
    }

    @Override
    public Optional<QueryCache> queryCache() {
      return Optional.of(this.queryCache);
    }

    /**
     * An {@link LRUQueryCache} subclass that instruments cache activity to expose runtime metrics
     * such as hit/miss counts, evictions, and memory usage.
     */
    private static class InstrumentingLruQueryCache extends LRUQueryCache {

      private final Counter hitCountCounter;
      private final Counter missCountCounter;

      /**
       * Private constructor - does not register gauges. Use {@link #create} to construct instances.
       */
      private InstrumentingLruQueryCache(
          MetricsFactory metricsFactory, int maxSize, long maxRamBytesUsed) {
        super(maxSize, maxRamBytesUsed);
        this.hitCountCounter = metricsFactory.counter("hitCount");
        this.missCountCounter = metricsFactory.counter("missCount");
      }

      /**
       * Creates a new InstrumentingLruQueryCache and registers gauges after construction to avoid
       * this-escape.
       */
      public static InstrumentingLruQueryCache create(
          MetricsFactory metricsFactory, int maxSize, long maxRamBytesUsed) {
        InstrumentingLruQueryCache cache =
            new InstrumentingLruQueryCache(metricsFactory, maxSize, maxRamBytesUsed);

        // Register gauges after construction is complete to avoid this-escape
        metricsFactory.objectValueGauge("cacheSize", cache, LRUQueryCache::getCacheSize);
        metricsFactory.objectValueGauge("evictionCount", cache, LRUQueryCache::getEvictionCount);
        metricsFactory.objectValueGauge("ramBytesUsed", cache, LRUQueryCache::ramBytesUsed);

        return cache;
      }

      @Override
      protected void onHit(Object readerCoreKey, Query query) {
        super.onHit(readerCoreKey, query);
        this.hitCountCounter.increment();
      }

      @Override
      protected void onMiss(Object readerCoreKey, Query query) {
        super.onMiss(readerCoreKey, query);
        this.missCountCounter.increment();
      }
    }
  }
}
