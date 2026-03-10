package com.xgen.mongot.index.lucene;

import com.xgen.mongot.index.query.counts.Count;
import com.xgen.mongot.index.query.sort.SequenceToken;
import java.io.IOException;
import java.util.Optional;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;

/**
 * The LuceneOperatorSearchManager is used to manage the execution of a single lucene search. It is
 * used by the {@link LuceneSearchIndexReader} to run an initial search and generate QueryInfo with
 * the info necessary to initialize a batch producer with the search results, and either to build
 * full {@link com.xgen.mongot.index.MetaResults} or initialize a {@link
 * com.xgen.mongot.index.CountMetaBatchProducer} to produce a single intermediate bucket with the
 * count.
 */
class LuceneOperatorSearchManager
    extends AbstractLuceneSearchManager<AbstractLuceneSearchManager.QueryInfo> {

  private final Count count;

  LuceneOperatorSearchManager(
      Query luceneQuery,
      Count count,
      Optional<Sort> luceneSort,
      Optional<SequenceToken> searchAfter,
      boolean hasIndexSort) {
    super(luceneQuery, luceneSort, searchAfter, hasIndexSort);
    this.count = count;
  }

  @Override
  public QueryInfo initialSearch(LuceneIndexSearcherReference searcherReference, int batchSize)
      throws IOException {

    int hitsThreshold =
        this.count.type() == Count.Type.TOTAL ? Integer.MAX_VALUE : this.count.threshold();

    // The IndexSearcher::search convenience APIs do not allow you to specify a hitsThreshold and
    // only count up to 1000 hits, so we explicitly create collectors that will do the hit counting
    // properly. Similarly, our FieldDoc may come from other shards and contain doc IDs that are
    // out of range. By using the collectors directly, we bypass checks on docID range.
    var collectorManager = createCollectorManager(batchSize, hitsThreshold);
    var topDocs =
        searcherReference.getIndexSearcher().search(this.getLuceneQuery(), collectorManager);

    maybePopulateScores(searcherReference.getIndexSearcher(), topDocs.scoreDocs);
    return new QueryInfo(topDocs, topDocs.scoreDocs.length < batchSize);
  }
}
