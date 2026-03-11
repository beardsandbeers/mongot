package com.xgen.mongot.index.lucene.query;

import static com.xgen.mongot.util.bson.FloatVector.OriginalType.NATIVE;

import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.definition.VectorIndexFilterFieldDefinition;
import com.xgen.mongot.index.definition.VectorQuantization;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.lucene.query.custom.MongotKnnFloatQuery;
import com.xgen.mongot.index.lucene.query.util.BooleanComposer;
import com.xgen.mongot.index.lucene.util.LuceneDoubleConversionUtils;
import com.xgen.mongot.index.query.VectorSearchQuery;
import com.xgen.mongot.index.query.operators.VectorSearchFilter;
import com.xgen.mongot.index.query.operators.mql.Clause;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.Vector;
import com.xgen.testing.mongot.index.definition.VectorDataFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.query.ApproximateVectorQueryCriteriaBuilder;
import com.xgen.testing.mongot.index.query.VectorQueryBuilder;
import com.xgen.testing.mongot.index.query.operators.mql.ClauseBuilder;
import com.xgen.testing.mongot.index.query.operators.mql.MqlFilterOperatorBuilder;
import com.xgen.testing.mongot.index.query.operators.mql.ValueBuilder;
import com.xgen.testing.mongot.mock.index.SearchIndex;
import java.util.List;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.bson.types.ObjectId;
import org.junit.Test;

public class EqualsVectorQueryFactoryTest {

  private static final IndexMetricsUpdater.QueryingMetricsUpdater metrics =
      new IndexMetricsUpdater.QueryingMetricsUpdater(SearchIndex.mockMetricsFactory());

  private static final String VECTOR_PATH = "vector";
  private static final String FILTER_PATH = "a";

  @Test
  public void testBoolean() throws Exception {

    var luceneQuery =
        new BooleanQuery.Builder()
            .add(
                new ConstantScoreQuery(new TermQuery(new Term("$type:boolean/a", "T"))),
                BooleanClause.Occur.MUST)
            .build();
    KnnFloatVectorQuery luceneVectorQuery =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            luceneQuery);

    Clause filter =
        ClauseBuilder.simpleClause()
            .path(FieldPath.parse(FILTER_PATH))
            .addOperator(MqlFilterOperatorBuilder.eq().value(ValueBuilder.bool(true)).build())
            .build();

    var mongotQuery = createVectorSearchQuery(filter);
    new LuceneVectorTranslation(
            List.of(
                VectorDataFieldDefinitionBuilder.builder()
                    .path(FieldPath.parse(VECTOR_PATH))
                    .numDimensions(3)
                    .similarity(VectorSimilarity.EUCLIDEAN)
                    .quantization(VectorQuantization.NONE)
                    .build(),
                VectorIndexFilterFieldDefinition.create(FieldPath.parse(FILTER_PATH))))
        .assertTranslatedTo(mongotQuery, luceneVectorQuery);
  }

  @Test
  public void testObjectId() throws Exception {
    var objectId = new ObjectId("507f1f77bcf86cd799439011");
    var luceneQuery =
        new BooleanQuery.Builder()
            .add(
                new ConstantScoreQuery(
                    new TermQuery(
                        new Term("$type:objectId/a", new BytesRef(objectId.toByteArray())))),
                BooleanClause.Occur.MUST)
            .build();
    KnnFloatVectorQuery luceneVectorQuery =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            luceneQuery);

    Clause filter =
        ClauseBuilder.simpleClause()
            .path(FieldPath.parse(FILTER_PATH))
            .addOperator(
                MqlFilterOperatorBuilder.eq().value(ValueBuilder.objectId(objectId)).build())
            .build();

    var mongotQuery = createVectorSearchQuery(filter);
    new LuceneVectorTranslation(
            List.of(
                VectorDataFieldDefinitionBuilder.builder()
                    .path(FieldPath.parse(VECTOR_PATH))
                    .numDimensions(3)
                    .similarity(VectorSimilarity.EUCLIDEAN)
                    .quantization(VectorQuantization.NONE)
                    .build(),
                VectorIndexFilterFieldDefinition.create(FieldPath.parse(FILTER_PATH))))
        .assertTranslatedTo(mongotQuery, luceneVectorQuery);
  }

  @Test
  public void testLong() throws Exception {

    // Check that an equals query for n translates to a range query for [n, n]
    var luceneQuery =
        new BooleanQuery.Builder()
            .add(
                BooleanComposer.constantScoreDisjunction(
                    BooleanComposer.constantScoreDisjunction(
                        new IndexOrDocValuesQuery(
                            org.apache.lucene.document.LongPoint.newRangeQuery(
                                "$type:int64/a", 2, 2),
                            NumericDocValuesField.newSlowRangeQuery("$type:int64/a", 2, 2)),
                        org.apache.lucene.document.LongPoint.newRangeQuery(
                            "$type:int64Multiple/a", 2, 2)),
                    BooleanComposer.constantScoreDisjunction(
                        new IndexOrDocValuesQuery(
                            org.apache.lucene.document.LongPoint.newRangeQuery(
                                "$type:double/a",
                                LuceneDoubleConversionUtils.toLong(2),
                                LuceneDoubleConversionUtils.toLong(2)),
                            NumericDocValuesField.newSlowRangeQuery(
                                "$type:double/a",
                                LuceneDoubleConversionUtils.toLong(2),
                                LuceneDoubleConversionUtils.toLong(2))),
                        org.apache.lucene.document.LongPoint.newRangeQuery(
                            "$type:doubleMultiple/a",
                            LuceneDoubleConversionUtils.toLong(2),
                            LuceneDoubleConversionUtils.toLong(2)))),
                BooleanClause.Occur.MUST)
            .build();

    KnnFloatVectorQuery luceneVectorQuery =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            luceneQuery);

    Clause filter =
        ClauseBuilder.simpleClause()
            .path(FieldPath.parse(FILTER_PATH))
            .addOperator(MqlFilterOperatorBuilder.eq().value(ValueBuilder.longNumber(2)).build())
            .build();

    var mongotQuery = createVectorSearchQuery(filter);
    new LuceneVectorTranslation(
            List.of(
                VectorDataFieldDefinitionBuilder.builder()
                    .path(FieldPath.parse(VECTOR_PATH))
                    .numDimensions(3)
                    .similarity(VectorSimilarity.EUCLIDEAN)
                    .quantization(VectorQuantization.NONE)
                    .build(),
                VectorIndexFilterFieldDefinition.create(FieldPath.parse(FILTER_PATH))))
        .assertTranslatedTo(mongotQuery, luceneVectorQuery);
  }

  @Test
  public void testString() throws Exception {
    var string = "example";

    var indexQuery =
        new ConstantScoreQuery(new TermQuery(new Term("$type:token/a", new BytesRef("example"))));
    var docValuesQuery =
        SortedSetDocValuesField.newSlowExactQuery("$type:token/a", new BytesRef("example"));

    var luceneQuery =
        new BooleanQuery.Builder()
            .add(new IndexOrDocValuesQuery(indexQuery, docValuesQuery), BooleanClause.Occur.MUST)
            .build();
    KnnFloatVectorQuery luceneVectorQuery =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            luceneQuery);

    Clause filter =
        ClauseBuilder.simpleClause()
            .path(FieldPath.parse(FILTER_PATH))
            .addOperator(MqlFilterOperatorBuilder.eq().value(ValueBuilder.string(string)).build())
            .build();

    var mongotQuery = createVectorSearchQuery(filter);
    new LuceneVectorTranslation(
            List.of(
                VectorDataFieldDefinitionBuilder.builder()
                    .path(FieldPath.parse(VECTOR_PATH))
                    .numDimensions(3)
                    .similarity(VectorSimilarity.EUCLIDEAN)
                    .quantization(VectorQuantization.NONE)
                    .build(),
                VectorIndexFilterFieldDefinition.create(FieldPath.parse(FILTER_PATH))))
        .assertTranslatedTo(mongotQuery, luceneVectorQuery);
  }

  private static VectorSearchQuery createVectorSearchQuery(Clause filter) {
    return VectorQueryBuilder.builder()
        .index("test")
        .criteria(
            ApproximateVectorQueryCriteriaBuilder.builder()
                .path(FieldPath.parse(VECTOR_PATH))
                .numCandidates(20)
                .limit(10)
                .queryVector(Vector.fromFloats(new float[] {1f, 2f, 3f}, NATIVE))
                .filter(new VectorSearchFilter.ClauseFilter(filter))
                .build())
        .build();
  }
}
