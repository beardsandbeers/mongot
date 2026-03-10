package com.xgen.mongot.index.lucene;

import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.lucene.searcher.LuceneIndexSearcher;
import com.xgen.mongot.index.query.sort.SequenceToken;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.bson.BsonInt64;

abstract class AbstractLuceneSearchManager<T> implements LuceneSearchManager<T> {

  private final Query luceneQuery;
  private final Optional<Sort> luceneSort;
  private final Optional<SequenceToken> searchAfter;
  private final boolean hasIndexSort;

  AbstractLuceneSearchManager(
      Query luceneQuery,
      Optional<Sort> luceneSort,
      Optional<SequenceToken> searchAfter,
      boolean hasIndexSort) {
    this.luceneQuery = luceneQuery;
    this.luceneSort = luceneSort;
    this.searchAfter = searchAfter;
    this.hasIndexSort = hasIndexSort;
  }

  @Override
  public TopDocs getMoreTopDocs(
      LuceneIndexSearcherReference searcherReference, ScoreDoc lastScoreDoc, int batchSize)
      throws IOException {

    TopDocs topDocs =
        this.luceneSort.isPresent()
            ? searcherReference
                .getIndexSearcher()
                .searchAfter(lastScoreDoc, this.luceneQuery, batchSize, this.luceneSort.get(), true)
            : searcherReference
                .getIndexSearcher()
                .searchAfter(lastScoreDoc, this.luceneQuery, batchSize);

    maybePopulateScores(searcherReference.getIndexSearcher(), topDocs.scoreDocs);
    return topDocs;
  }

  /**
   * Create a {@link CollectorManager} that will use concurrent segment search to find the top
   * {@code batchSize} docs matching the query and estimate hits up to {@code hitThreshold}.
   *
   * @param batchSize the maximum number of doc IDs to return
   * @param hitsThreshold the threshold beyond which we stop estimating hit counts accurately
   */
  protected CollectorManager<? extends TopDocsCollector<?>, ? extends TopDocs>
      createCollectorManager(int batchSize, int hitsThreshold) {
    Optional<FieldDoc> searchAfterFieldDoc =
        this.searchAfter
            .map(SequenceToken::fieldDoc)
            .map(
                fd ->
                    this.hasIndexSort && this.luceneSort.isPresent()
                        ? coerceSearchAfterFieldDoc(fd, this.luceneSort.get())
                        : fd);

    FieldDoc after = searchAfterFieldDoc.orElse(null);
    return this.luceneSort.isPresent()
        ? new TopFieldCollectorManager(
            this.luceneSort.get(), batchSize, after, hitsThreshold)
        : new TopScoreDocCollectorManager(batchSize, after, hitsThreshold);
  }

  /**
   * Coerces searchAfter token values for $meta/nullness sort fields from BsonInt64 to Long.
   *
   * <p>The $meta/nullness sort field uses a plain SortedNumericSortField whose comparator produces
   * raw Long values, unlike MqlLongSort/MqlDateSort which wrap their comparators in
   * FieldComparatorBsonWrapper to convert between Long and BsonValue. Until we introduce a
   * dedicated MqlNullnessSortField with proper BsonValue conversion, raw Long values must be
   * handled here during token deserialization.
   */
  private static FieldDoc coerceSearchAfterFieldDoc(FieldDoc fieldDoc, Sort sort) {
    if (fieldDoc.fields == null) {
      return fieldDoc;
    }

    SortField[] sortFields = sort.getSort();
    String nullnessPrefix = FieldName.MetaField.NULLNESS.getLuceneFieldName();

    Object[] coerced =
        IntStream.range(0, fieldDoc.fields.length)
            .mapToObj(
                i -> {
                  if (i < sortFields.length
                      && sortFields[i].getField() != null
                      && sortFields[i].getField().startsWith(nullnessPrefix)
                      && fieldDoc.fields[i] instanceof BsonInt64 b) {
                    return (Object) b.getValue();
                  }
                  return fieldDoc.fields[i];
                })
            .toArray();
    return new FieldDoc(fieldDoc.doc, fieldDoc.score, coerced, fieldDoc.shardIndex);
  }

  /**
   * Populates score values in each {@link ScoreDoc} if they are missing and required.
   *
   * <p>{@link org.apache.lucene.search.TopFieldCollector} does not compute scores unless the sort
   * criteria requires them. This saves time, but downstream aggregation stages may rely on
   * $searchScore, in which case we need to explicitly populate them here.
   */
  protected void maybePopulateScores(LuceneIndexSearcher searcher, ScoreDoc[] scoreDocs)
      throws IOException {
    if (this.luceneSort.isPresent()) {
      TopFieldCollector.populateScores(scoreDocs, searcher, this.getLuceneQuery());
    }
  }

  public Query getLuceneQuery() {
    return this.luceneQuery;
  }
}
