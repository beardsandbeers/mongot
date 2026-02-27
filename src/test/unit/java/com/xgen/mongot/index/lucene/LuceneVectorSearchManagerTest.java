package com.xgen.mongot.index.lucene;

import static com.google.common.truth.Truth.assertThat;
import static com.xgen.mongot.util.bson.FloatVector.OriginalType.NATIVE;
import static org.apache.lucene.index.VectorSimilarityFunction.EUCLIDEAN;

import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.index.lucene.codec.LuceneCodec;
import com.xgen.mongot.index.lucene.extension.KnnFloatVectorField;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.lucene.query.custom.MongotKnnFloatQuery;
import com.xgen.mongot.index.lucene.searcher.LuceneSearcherFactory;
import com.xgen.mongot.index.lucene.searcher.LuceneSearcherManager;
import com.xgen.mongot.index.lucene.searcher.QueryCacheProvider;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.FloatVector;
import com.xgen.mongot.util.bson.Vector;
import com.xgen.testing.mongot.index.query.ApproximateVectorQueryCriteriaBuilder;
import com.xgen.testing.mongot.index.query.VectorQueryBuilder;
import com.xgen.testing.mongot.mock.index.SearchIndex;
import com.xgen.testing.mongot.mock.index.VectorIndex;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.Test;

public class LuceneVectorSearchManagerTest {

  private static final IndexMetricsUpdater.QueryingMetricsUpdater metrics =
      new IndexMetricsUpdater.QueryingMetricsUpdater(SearchIndex.mockMetricsFactory());

  private final IndexWriter indexWriter;

  public LuceneVectorSearchManagerTest() throws IOException {
    this.indexWriter =
        new IndexWriter(
            new ByteBuffersDirectory(),
            new org.apache.lucene.index.IndexWriterConfig().setCodec(new LuceneCodec()));
  }

  @Test
  public void shouldCapResultsWithLimitWhenNumCandidatesIsHigherThanLimit() throws IOException {

    int numCandidates = 3;
    FloatVector queryVector = Vector.fromFloats(new float[] {4f, 4f}, NATIVE);
    var query =
        VectorQueryBuilder.builder()
            .index("myVectorIndex")
            .criteria(
                ApproximateVectorQueryCriteriaBuilder.builder()
                    .queryVector(queryVector)
                    .path(FieldPath.newRoot("root"))
                    .numCandidates(numCandidates)
                    .limit(2)
                    .build())
            .build();
    var luceneFieldName =
        FieldName.TypeField.KNN_VECTOR.getLuceneFieldName(
            FieldPath.newRoot("field"), Optional.empty());

    var manager =
        new LuceneVectorSearchManager(
            new MongotKnnFloatQuery(
                metrics, luceneFieldName, queryVector.getFloatVector(), numCandidates),
            query.criteria(),
            Optional.empty());

    // insert documents into Lucene
    Document doc1 = new Document();
    doc1.add(new KnnFloatVectorField(luceneFieldName, new float[] {1, 1}, EUCLIDEAN));
    this.indexWriter.addDocument(doc1);

    Document doc2 = new Document();
    doc2.add(new KnnFloatVectorField(luceneFieldName, new float[] {2, 2}, EUCLIDEAN));
    this.indexWriter.addDocument(doc2);

    Document doc3 = new Document();
    doc3.add(new KnnFloatVectorField(luceneFieldName, new float[] {3, 3}, EUCLIDEAN));
    this.indexWriter.addDocument(doc3);

    Document doc4 = new Document();
    doc4.add(new KnnFloatVectorField(luceneFieldName, new float[] {4, 4}, EUCLIDEAN));
    this.indexWriter.addDocument(doc4);

    this.indexWriter.commit();

    LuceneSearcherManager searcherManager =
        LuceneSearcherManager.create(
            this.indexWriter,
            new LuceneSearcherFactory(
                VectorIndex.MOCK_VECTOR_DEFINITION,
                false,
                new QueryCacheProvider.DefaultQueryCacheProvider(),
                Optional.empty(),
                VectorIndex.mockQueryMetricsUpdater()),
            VectorIndex.mockMetricsFactory());

    var searcherReference =
        LuceneIndexSearcherReference.create(
            searcherManager,
            SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH),
            FeatureFlags.getDefault());

    var initialScoreDocs = manager.initialSearch(searcherReference, 2).topDocs.scoreDocs;
    assertThat(Arrays.stream(initialScoreDocs).map(s -> s.doc)).containsExactly(3, 2).inOrder();
  }
}
