package com.xgen.mongot.index.lucene;

import com.xgen.mongot.index.lucene.searcher.LuceneIndexSearcher;
import com.xgen.mongot.index.query.InvalidQueryException;
import com.xgen.mongot.index.query.sort.SequenceToken;
import com.xgen.mongot.util.CheckedExceptionUtils;
import com.xgen.mongot.util.concurrent.NamedExecutorService;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.apache.lucene.facet.DrillSideways.DrillSidewaysResult;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;

/**
 * The LuceneFacetGenericDrillSidewaysSearchManager is used to manage the execution of a single
 * lucene search of a generic drill sideways faceted search. It performs a call to the Lucene Drill
 * Sideways API per facet. It is used by the {@link LuceneSearchIndexReader} to run an initial
 * search and generate info necessary to initialize a batch producer with the search results, and
 * either to build full {@link com.xgen.mongot.index.MetaResults} or initialize a {@link
 * LuceneFacetCollectorMetaBatchProducer} to produce intermediate meta buckets.
 */
class LuceneFacetGenericDrillSidewaysSearchManager
    extends AbstractLuceneSearchManager<
        LuceneFacetGenericDrillSidewaysSearchManager
            .GenericDrillSidewaysResultFacetCollectorQueryInfo> {

  public static class GenericDrillSidewaysResultFacetCollectorQueryInfo extends QueryInfo {

    public final Map<String, DrillSidewaysResult> facetResults;

    public GenericDrillSidewaysResultFacetCollectorQueryInfo(
        TopDocs topDocs, Map<String, DrillSidewaysResult> facetResults, boolean luceneExhausted) {
      super(topDocs, luceneExhausted);
      this.facetResults = facetResults;
    }
  }

  private final Map<String, LuceneDrillSideways> facetToDrillSidewaysFacetQueries;

  /**
   * Executor for running generic drill sideways concurrently. The presence of this optional
   * indicates that concurrency is enabled and this should be used.
   */
  private final Optional<NamedExecutorService> drillSidewaysConcurrentFacetExecutor;

  public LuceneFacetGenericDrillSidewaysSearchManager(
      Query luceneQuery,
      Map<String, LuceneDrillSideways> facetToDrillSidewaysFacetQueries,
      Optional<Sort> luceneSort,
      Optional<SequenceToken> searchAfter,
      Optional<NamedExecutorService> drillSidewaysConcurrentFacetExecutor,
      boolean hasIndexSort) {
    super(luceneQuery, luceneSort, searchAfter, hasIndexSort);
    this.facetToDrillSidewaysFacetQueries = facetToDrillSidewaysFacetQueries;
    this.drillSidewaysConcurrentFacetExecutor = drillSidewaysConcurrentFacetExecutor;
  }

  @Override
  public GenericDrillSidewaysResultFacetCollectorQueryInfo initialSearch(
      LuceneIndexSearcherReference searcherReference, int batchSize)
      throws IOException, InvalidQueryException {

    LuceneIndexSearcher searcher = searcherReference.getIndexSearcher();

    var readerLimit = Math.max(1, searcher.getIndexReader().maxDoc());
    var searchLimit = Math.min(readerLimit, batchSize);

    var searchCollectorManager = createCollectorManager(searchLimit, Integer.MAX_VALUE);
    var topDocs = searcher.search(this.getLuceneQuery(), searchCollectorManager);

    Map<String, DrillSidewaysResult> facetDrillSidewaysResults;
    if (this.drillSidewaysConcurrentFacetExecutor.isPresent()) {
      facetDrillSidewaysResults = executeDrillSidewaysConcurrently(searchCollectorManager);
    } else {
      facetDrillSidewaysResults = executeDrillSidewaysSequentially(searchCollectorManager);
    }

    maybePopulateScores(searcher, topDocs.scoreDocs);
    return new GenericDrillSidewaysResultFacetCollectorQueryInfo(
        topDocs, facetDrillSidewaysResults, topDocs.scoreDocs.length < batchSize);
  }

  private Map<String, DrillSidewaysResult> executeDrillSidewaysSequentially(
      CollectorManager<? extends TopDocsCollector<?>, ? extends TopDocs> searchCollectorManager)
      throws IOException, InvalidQueryException {
    Map<String, DrillSidewaysResult> facetDrillSidewaysResults = new HashMap<>();
    for (Map.Entry<String, LuceneDrillSideways> entry :
        this.facetToDrillSidewaysFacetQueries.entrySet()) {
      executeDrillSidewaysAndAddResult(searchCollectorManager, entry, facetDrillSidewaysResults);
    }
    return facetDrillSidewaysResults;
  }

  private Map<String, DrillSidewaysResult> executeDrillSidewaysConcurrently(
      CollectorManager<? extends TopDocsCollector<?>, ? extends TopDocs> searchCollectorManager)
      throws IOException, InvalidQueryException {

    Map<String, DrillSidewaysResult> facetDrillSidewaysResults = new ConcurrentHashMap<>();

    List<Future<Void>> drillSidewaysFacetExecutionResults =
        this.facetToDrillSidewaysFacetQueries.entrySet().stream()
            .map(
                (Function<Map.Entry<String, LuceneDrillSideways>, Future<Void>>)
                    entry ->
                        this.drillSidewaysConcurrentFacetExecutor
                            .get()
                            .submit(
                                () -> {
                                  executeDrillSidewaysAndAddResult(
                                      searchCollectorManager, entry, facetDrillSidewaysResults);
                                  return null;
                                }))
            .toList();

    for (Future<Void> result : drillSidewaysFacetExecutionResults) {
      try {
        result.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CompletionException(e);
      } catch (ExecutionException e) {
        CheckedExceptionUtils.propagateUnwrappedIfTypeElseRuntime(
            e, InvalidQueryException.class, IOException.class);
      }
    }
    return facetDrillSidewaysResults;
  }

  private static void executeDrillSidewaysAndAddResult(
      CollectorManager<? extends TopDocsCollector<?>, ? extends TopDocs> searchCollectorManager,
      Map.Entry<String, LuceneDrillSideways> entry,
      Map<String, DrillSidewaysResult> facetDrillSidewaysResults)
      throws IOException, InvalidQueryException {
    LuceneDrillSideways luceneDrillSideways = entry.getValue();
    MongotDrillSideways mongotDrillSideways = luceneDrillSideways.drillSideways();
    DrillSidewaysResult drillSidewaysResult =
        mongotDrillSideways.searchSafe(
            luceneDrillSideways.drillDownQuery(), searchCollectorManager);
    facetDrillSidewaysResults.put(entry.getKey(), drillSidewaysResult);
  }
}
