package com.xgen.mongot.index;

import static com.xgen.testing.mongot.mock.index.SearchIndex.MOCK_INDEX_DEFINITION;
import static com.xgen.testing.mongot.mock.index.VectorIndex.MOCK_AUTO_EMBEDDING_INDEX_DEFINITION;
import static com.xgen.testing.mongot.mock.index.VectorIndex.MOCK_VECTOR_DEFINITION;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.xgen.mongot.metrics.MetricsFactory;
import io.micrometer.core.instrument.Tags;
import org.junit.Test;

public class IndexTypeDataTest {

  @Test
  public void testGetIndexTypeTag_searchIndex() {
    IndexTypeData.IndexTypeTag result = IndexTypeData.getIndexTypeTag(MOCK_INDEX_DEFINITION);
    assertEquals(IndexTypeData.IndexTypeTag.TAG_SEARCH, result);
  }

  @Test
  public void testGetIndexTypeTag_vectorSearchIndex() {
    IndexTypeData.IndexTypeTag result = IndexTypeData.getIndexTypeTag(MOCK_VECTOR_DEFINITION);
    assertEquals(IndexTypeData.IndexTypeTag.TAG_VECTOR_SEARCH, result);
  }

  @Test
  public void testGetIndexTypeTag_autoEmbeddingIndex() {
    IndexTypeData.IndexTypeTag result =
        IndexTypeData.getIndexTypeTag(MOCK_AUTO_EMBEDDING_INDEX_DEFINITION);
    assertEquals(IndexTypeData.IndexTypeTag.TAG_VECTOR_SEARCH_AUTO_EMBEDDING, result);
  }

  @Test
  public void testGetNumGauge_withSearchTag() {
    MetricsFactory metricsFactory = mock(MetricsFactory.class);
    String gaugeName = "withSearchTag";
    Tags expectedTags = Tags.of(IndexTypeData.INDEX_TYPE_TAG_NAME, "search");
    IndexTypeData.getNumGauge(metricsFactory, gaugeName, IndexTypeData.IndexTypeTag.TAG_SEARCH);
    verify(metricsFactory).numGauge(eq(gaugeName), eq(expectedTags));
  }

  @Test
  public void testGetNumGauge_withVectorSearchTag() {
    MetricsFactory metricsFactory = mock(MetricsFactory.class);
    String gaugeName = "withVectorSearchTag";
    Tags expectedTags = Tags.of(IndexTypeData.INDEX_TYPE_TAG_NAME, "vector_search");
    IndexTypeData.getNumGauge(
        metricsFactory, gaugeName, IndexTypeData.IndexTypeTag.TAG_VECTOR_SEARCH);
    verify(metricsFactory).numGauge(eq(gaugeName), eq(expectedTags));
  }

  @Test
  public void testGetNumGauge_withVectorSearchAutoEmbeddingTag() {
    MetricsFactory metricsFactory = mock(MetricsFactory.class);
    String gaugeName = "withVectorSearchAutoEmbeddingTag";
    Tags expectedTags = Tags.of(IndexTypeData.INDEX_TYPE_TAG_NAME, "vector_search_auto_embedding");
    IndexTypeData.getNumGauge(
        metricsFactory, gaugeName, IndexTypeData.IndexTypeTag.TAG_VECTOR_SEARCH_AUTO_EMBEDDING);
    verify(metricsFactory).numGauge(eq(gaugeName), eq(expectedTags));
  }
}
