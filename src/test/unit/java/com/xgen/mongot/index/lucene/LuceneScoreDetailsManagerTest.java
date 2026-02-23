package com.xgen.mongot.index.lucene;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xgen.mongot.index.ScoreDetails;
import com.xgen.mongot.index.query.QueryOptimizationFlags;
import com.xgen.testing.mongot.index.ScoreDetailsBuilder;
import com.xgen.testing.mongot.index.query.QueryOptimizationFlagsBuilder;
import java.io.IOException;
import java.util.List;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.junit.Assert;
import org.junit.Test;

public class LuceneScoreDetailsManagerTest {
  @Test
  public void testPlaceholderExplanationWhenIndexSearcherExplainReturnsNull() throws IOException {
    Query query = mock(Query.class);
    String queryName = "cool_query";
    doReturn(queryName).when(query).toString();

    IndexSearcher indexSearcher = mock(IndexSearcher.class);
    doReturn(null).when(indexSearcher).explain(any(Query.class), anyInt());

    int score = 1;
    List<ScoreDetails> scoreDetails =
        new LuceneScoreDetailsManager(query)
            .getScoreDetails(
                indexSearcher,
                new TopDocs(
                    new TotalHits(1L, TotalHits.Relation.EQUAL_TO),
                    new ScoreDoc[] {new ScoreDoc(0, score)}));
    Assert.assertEquals(1, scoreDetails.size());
    ScoreDetails scoreDetail = scoreDetails.get(0);

    Assert.assertEquals(score, scoreDetail.getValue(), 0d);
    Assert.assertEquals(queryName, scoreDetail.getDescription());
  }

  @Test
  public void testPlaceholderExplanationWhenIndexSearcherThrowsNullPointerException()
      throws IOException {
    Query query = mock(Query.class);
    String queryName = "cool_query";
    doReturn(queryName).when(query).toString();

    IndexSearcher indexSearcher = mock(IndexSearcher.class);
    doThrow(NullPointerException.class).when(indexSearcher).explain(any(Query.class), anyInt());

    int score = 1;
    List<ScoreDetails> scoreDetails =
        new LuceneScoreDetailsManager(query)
            .getScoreDetails(
                indexSearcher,
                new TopDocs(
                    new TotalHits(1L, TotalHits.Relation.EQUAL_TO),
                    new ScoreDoc[] {new ScoreDoc(0, score)}));
    Assert.assertEquals(1, scoreDetails.size());
    ScoreDetails scoreDetail = scoreDetails.get(0);

    Assert.assertEquals(score, scoreDetail.getValue(), 0d);
    Assert.assertEquals(queryName, scoreDetail.getDescription());
  }

  @Test
  public void testPlaceholderExplanationWhenIndexSearcherThrowsUnsupportedOperationException()
      throws IOException {
    Query query = mock(Query.class);
    String queryName = "cool_query";
    doReturn(queryName).when(query).toString();

    IndexSearcher indexSearcher = mock(IndexSearcher.class);
    doThrow(UnsupportedOperationException.class)
        .when(indexSearcher)
        .explain(any(Query.class), anyInt());

    int score = 1;
    List<ScoreDetails> scoreDetails =
        new LuceneScoreDetailsManager(query)
            .getScoreDetails(
                indexSearcher,
                new TopDocs(
                    new TotalHits(1L, TotalHits.Relation.EQUAL_TO),
                    new ScoreDoc[] {new ScoreDoc(0, score)}));
    Assert.assertEquals(1, scoreDetails.size());
    ScoreDetails scoreDetail = scoreDetails.get(0);

    Assert.assertEquals(score, scoreDetail.getValue(), 0d);
    Assert.assertEquals(queryName, scoreDetail.getDescription());
  }

  @Test
  public void testSuccessfulThenNullThenExceptionalExplain() throws IOException {
    Query query = mock(Query.class);
    String queryName = "cool_query";
    doReturn(queryName).when(query).toString();

    IndexSearcher indexSearcher = mock(IndexSearcher.class);
    String successfulDescription = "successful explain";
    when(indexSearcher.explain(any(Query.class), anyInt()))
        .thenReturn(Explanation.match(10f, successfulDescription))
        .thenThrow(UnsupportedOperationException.class)
        .thenReturn(null);

    List<ScoreDetails> expected =
        List.of(
            ScoreDetailsBuilder.builder().value(10f).description(successfulDescription).build(),
            ScoreDetailsBuilder.builder().value(5f).description(queryName).build(),
            ScoreDetailsBuilder.builder().value(1f).description(queryName).build());

    List<ScoreDetails> actual =
        new LuceneScoreDetailsManager(query)
            .getScoreDetails(
                indexSearcher,
                new TopDocs(
                    new TotalHits(1L, TotalHits.Relation.EQUAL_TO),
                    List.of(new ScoreDoc(0, 10f), new ScoreDoc(1, 5f), new ScoreDoc(2, 1f))
                        .toArray(ScoreDoc[]::new)));

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testOmitSearchDocumentResultOmitsScoreDetails() {
    assertThat(
            LuceneScoreDetailsManager.getScoreDetailsManagerIfPresent(
                true,
                mock(Query.class),
                QueryOptimizationFlagsBuilder.builder().omitSearchDocumentResults(true).build()))
        .isEmpty();
  }

  @Test
  public void testScoreDetailsFalseOmitsScoreDetails() {
    assertThat(
            LuceneScoreDetailsManager.getScoreDetailsManagerIfPresent(
                false, mock(Query.class), QueryOptimizationFlags.DEFAULT_OPTIONS))
        .isEmpty();
  }
}
