package com.xgen.mongot.index.lucene.quantization;

import static com.google.common.truth.Truth.assertThat;
import static com.xgen.mongot.util.bson.FloatVector.OriginalType.NATIVE;
import static org.apache.lucene.index.VectorSimilarityFunction.COSINE;
import static org.apache.lucene.index.VectorSimilarityFunction.DOT_PRODUCT;
import static org.apache.lucene.index.VectorSimilarityFunction.EUCLIDEAN;

import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.index.definition.VectorFieldSpecification;
import com.xgen.mongot.index.definition.VectorIndexingAlgorithm;
import com.xgen.mongot.index.definition.VectorQuantization;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.lucene.LuceneIndexSearcherReference;
import com.xgen.mongot.index.lucene.LuceneVectorSearchManager;
import com.xgen.mongot.index.lucene.codec.LuceneCodec;
import com.xgen.mongot.index.lucene.extension.KnnFloatVectorField;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.lucene.query.custom.MongotKnnFloatQuery;
import com.xgen.mongot.index.lucene.searcher.LuceneSearcherFactory;
import com.xgen.mongot.index.lucene.searcher.LuceneSearcherManager;
import com.xgen.mongot.index.lucene.searcher.QueryCacheProvider;
import com.xgen.mongot.index.query.VectorSearchQuery;
import com.xgen.mongot.index.query.operators.ApproximateVectorSearchCriteria;
import com.xgen.mongot.util.Check;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.Vector;
import com.xgen.mongot.util.concurrent.Executors;
import com.xgen.mongot.util.concurrent.NamedExecutorService;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.query.ApproximateVectorQueryCriteriaBuilder;
import com.xgen.testing.mongot.index.query.VectorQueryBuilder;
import com.xgen.testing.mongot.mock.index.SearchIndex;
import com.xgen.testing.mongot.mock.index.VectorIndex;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class BinaryQuantizedVectorRescorerTest {

  private static final IndexMetricsUpdater.QueryingMetricsUpdater metrics =
      new IndexMetricsUpdater.QueryingMetricsUpdater(SearchIndex.mockMetricsFactory());

  private final IndexWriter indexWriter;
  private final String vectorFieldName;

  public final Optional<NamedExecutorService> executor;

  public final boolean sparse;
  private final VectorSimilarityFunction similarity;

  record TestSpec(
      boolean sparse, VectorSimilarityFunction sim, Optional<NamedExecutorService> executor) {}

  @Parameters(name = "{0}")
  public static Collection<TestSpec> data() {
    List<TestSpec> params = new ArrayList<>();

    for (VectorSimilarityFunction sim : List.of(DOT_PRODUCT, COSINE, EUCLIDEAN)) {
      for (boolean sparse : new boolean[] {true, false}) {
        for (var executor :
            List.of(
                Optional.<NamedExecutorService>empty(),
                Optional.of(
                    Executors.namedExecutor(
                        "test",
                        java.util.concurrent.Executors.newFixedThreadPool(2),
                        new SimpleMeterRegistry())))) {
          params.add(new TestSpec(sparse, sim, executor));
        }
      }
    }
    return params;
  }

  public BinaryQuantizedVectorRescorerTest(TestSpec params) throws IOException {
    var fieldPath = FieldPath.newRoot("field");
    this.sparse = params.sparse;
    this.executor = params.executor;
    this.similarity = params.sim;

    this.indexWriter =
        new IndexWriter(
            new ByteBuffersDirectory(),
            new IndexWriterConfig()
                .setMergePolicy(NoMergePolicy.INSTANCE)
                .setCodec(
                    new LuceneCodec(
                        Map.of(
                            fieldPath,
                            new VectorFieldSpecification(
                                2,
                                VectorSimilarity.COSINE,
                                VectorQuantization.BINARY,
                                new VectorIndexingAlgorithm.HnswIndexingAlgorithm())))));

    this.vectorFieldName =
        FieldName.TypeField.KNN_F32_Q1.getLuceneFieldName(fieldPath, Optional.empty());
  }

  @Parameterized.AfterParam
  public static void cleanup(TestSpec spec) {
    spec.executor.ifPresent(ExecutorService::close);
  }

  @Test
  public void dequantizedRescoring_euclidean_returnsResultsInOrder() throws IOException {
    Assume.assumeTrue(this.similarity == EUCLIDEAN);
    // insert documents into Lucene
    indexDocumentsAndCommit(
        List.of(
            new float[] {10, -10},
            new float[] {20, 20},
            new float[] {10, -5},
            new float[] {30, 30},
            new float[] {3, -3},
            new float[] {50, 50}),
        EUCLIDEAN);
    createEmptySegment(2);

    // create search manager
    LuceneVectorSearchManager manager = createSearchManager(new float[] {1, -1}, 10, 100);
    KnnFloatVectorQuery luceneQuery =
        Check.instanceOf(manager.getLuceneQuery(), KnnFloatVectorQuery.class);

    var searcherManager =
        LuceneSearcherManager.create(
            this.indexWriter,
            new LuceneSearcherFactory(
                VectorIndex.MOCK_VECTOR_DEFINITION,
                false,
                new QueryCacheProvider.DefaultQueryCacheProvider(),
                Optional.empty(),
                SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH)),
            VectorIndex.mockMetricsFactory());
    var searcherReference =
        LuceneIndexSearcherReference.create(
            searcherManager,
            SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH),
            FeatureFlags.getDefault());
    var firstPassTopDocs = manager.initialSearch(searcherReference, 10).topDocs;
    var approximateRescorer = new BinaryQuantizedVectorRescorer.ApproximateRescorer(luceneQuery);

    TopDocs fullResult =
        approximateRescorer.rescore(searcherReference.getIndexSearcher(), firstPassTopDocs, 10);
    Assert.assertEquals(6, fullResult.scoreDocs.length);
    // check that docs 0 and 2 are scored higher than 1 and 3 (but can't guarantee the exact order)
    assertThat(Stream.of(Arrays.copyOf(fullResult.scoreDocs, 3)).mapToInt(value -> value.doc))
        .containsExactly(0, 2, 4);
    assertThat(
            Stream.of(Arrays.copyOfRange(fullResult.scoreDocs, 3, 6)).mapToInt(value -> value.doc))
        .containsExactly(1, 3, 5);

    TopDocs subsetResult =
        approximateRescorer.rescore(searcherReference.getIndexSearcher(), firstPassTopDocs, 2);
    Assert.assertEquals(2, subsetResult.scoreDocs.length);
    assertThat(Stream.of(subsetResult.scoreDocs).mapToInt(value -> value.doc))
        .containsExactly(0, 2);
    assertScoresValid(subsetResult);
  }

  @Test
  public void dequantizedRescoring_allSimilarities_returnsResultsInOrder() throws IOException {
    // Temporarily skip DOT_PRODUCT due to incorrectly clamping of intermediate result.
    Assume.assumeFalse(this.similarity == DOT_PRODUCT);

    // insert unit vectors since they are rank equivalent.
    indexDocumentsAndCommit(
        List.of(
            new float[] {-.7f, -.7f, -.1f},
            new float[] {-.7f, -.7f, .1f},
            new float[] {.7f, .7f, .1f}),
        this.similarity);
    createEmptySegment(3);

    // create search manager
    LuceneVectorSearchManager manager =
        createSearchManager(new float[] {.96f, .28f, .01f}, 10, 100);
    KnnFloatVectorQuery luceneQuery =
        Check.instanceOf(manager.getLuceneQuery(), KnnFloatVectorQuery.class);
    var searcherManager =
        LuceneSearcherManager.create(
            this.indexWriter,
            new LuceneSearcherFactory(
                VectorIndex.MOCK_VECTOR_DEFINITION,
                false,
                new QueryCacheProvider.DefaultQueryCacheProvider(),
                Optional.empty(),
                SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH)),
            VectorIndex.mockMetricsFactory());
    var searcherReference =
        LuceneIndexSearcherReference.create(
            searcherManager,
            SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH),
            FeatureFlags.getDefault());
    var firstPassTopDocs = manager.initialSearch(searcherReference, 10).topDocs;
    var approximateRescorer = new BinaryQuantizedVectorRescorer.ApproximateRescorer(luceneQuery);

    TopDocs fullResult =
        approximateRescorer.rescore(searcherReference.getIndexSearcher(), firstPassTopDocs, 10);
    TestUtils.assertHasDocIds(fullResult.scoreDocs, 2, 1, 0);
  }

  @Test
  public void fullFidelityRescoring_euclidean_returnsResultsInOrder() throws IOException {
    Assume.assumeTrue(this.similarity == EUCLIDEAN);
    // insert documents into Lucene
    indexDocumentsAndCommit(
        List.of(new float[] {1, 1}, new float[] {2, 2}, new float[] {10, 10}, new float[] {11, 11}),
        EUCLIDEAN);
    createEmptySegment(2);

    // create search manager
    LuceneVectorSearchManager manager = createSearchManager(new float[] {9, 9}, 10, 100);
    KnnFloatVectorQuery luceneQuery =
        Check.instanceOf(manager.getLuceneQuery(), KnnFloatVectorQuery.class);

    var searcherManager =
        LuceneSearcherManager.create(
            this.indexWriter,
            new LuceneSearcherFactory(
                VectorIndex.MOCK_VECTOR_DEFINITION,
                false,
                new QueryCacheProvider.DefaultQueryCacheProvider(),
                Optional.empty(),
                SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH)),
            VectorIndex.mockMetricsFactory());

    var searcherReference =
        LuceneIndexSearcherReference.create(
            searcherManager,
            SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH),
            FeatureFlags.getDefault());
    var firstPassTopDocs = manager.initialSearch(searcherReference, 10).topDocs;
    var rescorer =
        new BinaryQuantizedVectorRescorer.FullFidelityRescorer(luceneQuery, this.executor);

    TopDocs fullResult =
        rescorer.rescore(searcherReference.getIndexSearcher(), firstPassTopDocs, 10);
    assertThat(Stream.of(fullResult.scoreDocs).mapToInt(value -> value.doc))
        .containsExactly(2, 3, 1, 0)
        .inOrder();

    TopDocs subsetResult =
        rescorer.rescore(searcherReference.getIndexSearcher(), firstPassTopDocs, 2);
    assertThat(Stream.of(subsetResult.scoreDocs).mapToInt(value -> value.doc))
        .containsExactly(1, 0)
        .inOrder();
    assertScoresValid(subsetResult);
  }

  @Test
  public void basicTwoStageRescoring_euclidean_returnsResultsInOrder() throws IOException {
    Assume.assumeTrue(this.similarity == EUCLIDEAN);

    // insert documents into Lucene
    indexDocumentsAndCommit(
        List.of(
            new float[] {10, -10},
            new float[] {20, 20},
            new float[] {10, -5},
            new float[] {50, 50}),
        EUCLIDEAN);
    createEmptySegment(2);

    // create search manager
    LuceneVectorSearchManager manager = createSearchManager(new float[] {1, -1}, 10, 100);
    KnnFloatVectorQuery luceneQuery =
        Check.instanceOf(manager.getLuceneQuery(), KnnFloatVectorQuery.class);

    var searcherManager =
        LuceneSearcherManager.create(
            this.indexWriter,
            new LuceneSearcherFactory(
                VectorIndex.MOCK_VECTOR_DEFINITION,
                false,
                new QueryCacheProvider.DefaultQueryCacheProvider(),
                Optional.empty(),
                SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH)),
            VectorIndex.mockMetricsFactory());

    var searcherReference =
        LuceneIndexSearcherReference.create(
            searcherManager,
            SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH),
            FeatureFlags.getDefault());
    var firstPass = manager.initialSearch(searcherReference, 10).topDocs;

    var rescorer = new BinaryQuantizedVectorRescorer(this.executor);
    TopDocs fullResult =
        rescorer.rescore(
            searcherReference.getIndexSearcher(),
            new TopDocs(firstPass.totalHits, firstPass.scoreDocs.clone()),
            createVectorSearchQuery(10, 20),
            luceneQuery);

    assertThat(Stream.of(fullResult.scoreDocs).mapToInt(value -> value.doc))
        .containsExactly(2, 0, 1, 3)
        .inOrder();
    assertScoresValid(fullResult);
  }

  @Test
  public void basicTwoStageRescoringSubset_euclidean_returnsResultsInOrder() throws IOException {
    Assume.assumeTrue(this.similarity == EUCLIDEAN);

    // insert documents into Lucene
    indexDocumentsAndCommit(
        List.of(
            new float[] {10, -10},
            new float[] {20, 20},
            new float[] {10, -5},
            new float[] {50, 50}),
        EUCLIDEAN);
    createEmptySegment(2);

    // create search manager
    LuceneVectorSearchManager manager = createSearchManager(new float[] {1, -1}, 10, 100);
    KnnFloatVectorQuery luceneQuery =
        Check.instanceOf(manager.getLuceneQuery(), KnnFloatVectorQuery.class);

    var searcherManager =
        LuceneSearcherManager.create(
            this.indexWriter,
            new LuceneSearcherFactory(
                VectorIndex.MOCK_VECTOR_DEFINITION,
                false,
                new QueryCacheProvider.DefaultQueryCacheProvider(),
                Optional.empty(),
                SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH)),
            VectorIndex.mockMetricsFactory());

    var searcherReference =
        LuceneIndexSearcherReference.create(
            searcherManager,
            SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH),
            FeatureFlags.getDefault());
    var firstPass = manager.initialSearch(searcherReference, 10).topDocs;

    var rescorer = new BinaryQuantizedVectorRescorer(this.executor);
    TopDocs subsetResult =
        rescorer.rescore(
            searcherReference.getIndexSearcher(),
            new TopDocs(firstPass.totalHits, firstPass.scoreDocs.clone()),
            createVectorSearchQuery(2, 20),
            luceneQuery);

    assertThat(Stream.of(subsetResult.scoreDocs).mapToInt(value -> value.doc))
        .containsExactly(2, 0)
        .inOrder();
    assertScoresValid(subsetResult);
  }

  @Test
  public void multiSegmentTwoStageRescoring_euclidean_returnsResultsInOrder() throws IOException {
    Assume.assumeFalse(this.sparse);
    Assume.assumeTrue(this.similarity == EUCLIDEAN);

    // insert documents in multiple batches, effectively producing multiple segments
    indexDocumentsAndCommit(List.of(new float[] {10, -10}, new float[] {20, 20}), EUCLIDEAN);
    indexDocumentsAndCommit(List.of(new float[] {10, -5}, new float[] {30, 30}), EUCLIDEAN);
    indexDocumentsAndCommit(List.of(new float[] {3, -3}, new float[] {50, 50}), EUCLIDEAN);
    createEmptySegment(2);

    // create search manager
    LuceneVectorSearchManager manager = createSearchManager(new float[] {1, -1}, 10, 100);
    KnnFloatVectorQuery luceneQuery =
        Check.instanceOf(manager.getLuceneQuery(), KnnFloatVectorQuery.class);

    var searcherManager =
        LuceneSearcherManager.create(
            this.indexWriter,
            new LuceneSearcherFactory(
                VectorIndex.MOCK_VECTOR_DEFINITION,
                false,
                new QueryCacheProvider.DefaultQueryCacheProvider(),
                Optional.empty(),
                SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH)),
            VectorIndex.mockMetricsFactory());

    var searcherReference =
        LuceneIndexSearcherReference.create(
            searcherManager,
            SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH),
            FeatureFlags.getDefault());
    var firstPassTopDocs = manager.initialSearch(searcherReference, 10).topDocs;

    var rescorer = new BinaryQuantizedVectorRescorer(this.executor);
    TopDocs result =
        rescorer.rescore(
            searcherReference.getIndexSearcher(),
            firstPassTopDocs,
            createVectorSearchQuery(10, 20),
            luceneQuery);

    assertThat(Stream.of(result.scoreDocs).mapToInt(value -> value.doc))
        .containsExactly(4, 2, 0, 1, 3, 5)
        .inOrder();
    assertScoresValid(result);
  }

  @Test
  public void twoStageRescoring_allSimilarities_isIdempotent() throws IOException {
    // insert random documents into Lucene
    Supplier<Float> generator = () -> (float) (ThreadLocalRandom.current().nextDouble() - 0.5);
    List<float[]> vectors =
        Stream.generate(
                () ->
                    new float[] {
                      generator.get(), generator.get(), generator.get(), generator.get()
                    })
            .limit(100)
            .toList();
    indexDocumentsAndCommit(vectors, this.similarity);
    createEmptySegment(4);

    // create search manager
    LuceneVectorSearchManager manager =
        createSearchManager(
            new float[] {generator.get(), generator.get(), generator.get(), generator.get()},
            50,
            100);
    KnnFloatVectorQuery luceneQuery =
        Check.instanceOf(manager.getLuceneQuery(), KnnFloatVectorQuery.class);

    var searcherManager =
        LuceneSearcherManager.create(
            this.indexWriter,
            new LuceneSearcherFactory(
                VectorIndex.MOCK_VECTOR_DEFINITION,
                false,
                new QueryCacheProvider.DefaultQueryCacheProvider(),
                Optional.empty(),
                SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH)),
            VectorIndex.mockMetricsFactory());

    var searcherReference =
        LuceneIndexSearcherReference.create(
            searcherManager,
            SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.VECTOR_SEARCH),
            FeatureFlags.getDefault());
    var firstPassTopDocs = manager.initialSearch(searcherReference, 10).topDocs;

    var rescorer = new BinaryQuantizedVectorRescorer(this.executor);
    TopDocs firstRescore =
        rescorer.rescore(
            searcherReference.getIndexSearcher(),
            new TopDocs(firstPassTopDocs.totalHits, firstPassTopDocs.scoreDocs.clone()),
            createVectorSearchQuery(10, 20),
            luceneQuery);
    TopDocs secondRescore =
        rescorer.rescore(
            searcherReference.getIndexSearcher(),
            new TopDocs(firstPassTopDocs.totalHits, firstPassTopDocs.scoreDocs.clone()),
            createVectorSearchQuery(10, 20),
            luceneQuery);

    assertScoresValid(firstRescore);
    Assert.assertEquals(
        Stream.of(firstRescore.scoreDocs).map(doc -> doc.doc).toList(),
        Stream.of(secondRescore.scoreDocs).map(doc -> doc.doc).toList());
  }

  /** Assert all final scores are [0, 1], which is required for vectorSearch. */
  private static void assertScoresValid(TopDocs topDocs) {
    for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
      assertThat(scoreDoc.score).isAtLeast(0f);
      assertThat(scoreDoc.score).isAtMost(1f);
    }
  }

  /** Create a segment with ghost vector values. */
  private void createEmptySegment(int dim) throws IOException {
    float[] v = new float[dim];
    v[1] = 1f;
    var toBeDeleted = new Document();
    toBeDeleted.add(new TextField("delete", "delete", Field.Store.NO));
    toBeDeleted.add(new KnnFloatVectorField(this.vectorFieldName, v, this.similarity));

    var docToKeepSegmentLive = new Document();
    docToKeepSegmentLive.add(new StringField("keep", "keep", Field.Store.NO));
    this.indexWriter.addDocument(docToKeepSegmentLive);
    this.indexWriter.addDocument(toBeDeleted);
    this.indexWriter.commit();

    this.indexWriter.deleteDocuments(new Term("delete", "delete"));
    this.indexWriter.commit();
  }

  private void indexDocumentsAndCommit(List<float[]> vectors, VectorSimilarityFunction similarity)
      throws IOException {

    for (float[] vector : vectors) {
      var document = new Document();
      document.add(new KnnFloatVectorField(this.vectorFieldName, vector, similarity));
      this.indexWriter.addDocument(document);
    }

    // If set, index a document without a vector field to trigger SparseOffHeapVectorValue path
    if (this.sparse) {
      this.indexWriter.addDocument(new Document());
    }

    this.indexWriter.commit();
  }

  private LuceneVectorSearchManager createSearchManager(
      float[] queryVector, int limit, int numCandidates) {

    VectorSearchQuery vectorQuery =
        VectorQueryBuilder.builder()
            .index("myVectorIndex")
            .criteria(
                ApproximateVectorQueryCriteriaBuilder.builder()
                    .queryVector(Vector.fromFloats(queryVector, NATIVE))
                    .path(FieldPath.newRoot("root"))
                    .limit(limit)
                    .numCandidates(numCandidates)
                    .build())
            .build();

    KnnFloatVectorQuery luceneQuery =
        new MongotKnnFloatQuery(metrics, this.vectorFieldName, queryVector, numCandidates);

    return new LuceneVectorSearchManager(luceneQuery, vectorQuery.criteria(), Optional.empty());
  }

  private static ApproximateVectorSearchCriteria createVectorSearchQuery(
      int limit, int numCandidates) {
    return ApproximateVectorQueryCriteriaBuilder.builder()
        .path(FieldPath.newRoot("field"))
        .queryVector(Vector.fromFloats(new float[] {}, NATIVE))
        .limit(limit)
        .numCandidates(numCandidates)
        .build();
  }
}
