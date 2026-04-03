package com.xgen.mongot.index.lucene.query;

import static com.xgen.mongot.util.bson.FloatVector.OriginalType.NATIVE;

import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.definition.VectorIndexFilterFieldDefinition;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import com.xgen.mongot.index.lucene.query.custom.MongotKnnFloatQuery;
import com.xgen.mongot.index.lucene.query.util.BooleanComposer;
import com.xgen.mongot.index.lucene.util.LuceneDoubleConversionUtils;
import com.xgen.mongot.index.query.VectorSearchQuery;
import com.xgen.mongot.index.query.operators.VectorSearchFilter;
import com.xgen.mongot.index.query.operators.mql.MqlFilterOperator;
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
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery.Builder;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.Query;
import org.junit.Test;

public class NumericRangeVectorQueryFactoriesTest {

  private static final IndexMetricsUpdater.QueryingMetricsUpdater metrics =
      new IndexMetricsUpdater.QueryingMetricsUpdater(SearchIndex.mockMetricsFactory());

  private static final String FILTER_PATH = "a";
  private static final String VECTOR_PATH = "vector";

  @Test
  public void testLong() throws Exception {
    List<MqlFilterOperator> operators =
        List.of(MqlFilterOperatorBuilder.lte().value(ValueBuilder.longNumber(1L)).build());

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new Builder()
                .add(
                    new ConstantScoreQuery(
                        new Builder()
                            .add(createLongLuceneRangeQueries(Long.MIN_VALUE, 1L), Occur.SHOULD)
                            .add(
                                createDoubleLuceneRangeQueries(-1.0 * Double.MAX_VALUE, 1.0),
                                Occur.SHOULD)
                            .build()),
                    Occur.MUST)
                .build());

    getLuceneVectorTranslation().assertTranslatedTo(getVectorQuery(operators), expected);
  }

  @Test
  public void testInteger() throws Exception {
    List<MqlFilterOperator> operators =
        List.of(MqlFilterOperatorBuilder.gte().value(ValueBuilder.intNumber(1)).build());

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new Builder()
                .add(
                    new ConstantScoreQuery(
                        new Builder()
                            .add(createLongLuceneRangeQueries(1, Long.MAX_VALUE), Occur.SHOULD)
                            .add(
                                createDoubleLuceneRangeQueries(1.0, 1.0 * Double.MAX_VALUE),
                                Occur.SHOULD)
                            .build()),
                    Occur.MUST)
                .build());

    getLuceneVectorTranslation().assertTranslatedTo(getVectorQuery(operators), expected);
  }

  @Test
  public void testDouble() throws Exception {
    List<MqlFilterOperator> operators =
        List.of(
            MqlFilterOperatorBuilder.gte().value(ValueBuilder.doubleNumber(2)).build(),
            MqlFilterOperatorBuilder.lte().value(ValueBuilder.doubleNumber(9)).build());

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new Builder()
                .add(
                    new ConstantScoreQuery(
                        new Builder()
                            .add(createLongLuceneRangeQueries(2, Long.MAX_VALUE), Occur.SHOULD)
                            .add(
                                createDoubleLuceneRangeQueries(2.0, 1.0 * Double.MAX_VALUE),
                                Occur.SHOULD)
                            .build()),
                    Occur.MUST)
                .add(
                    new ConstantScoreQuery(
                        new Builder()
                            .add(createLongLuceneRangeQueries(Long.MIN_VALUE, 9), Occur.SHOULD)
                            .add(
                                createDoubleLuceneRangeQueries(-1.0 * Double.MAX_VALUE, 9),
                                Occur.SHOULD)
                            .build()),
                    Occur.MUST)
                .build());

    getLuceneVectorTranslation().assertTranslatedTo(getVectorQuery(operators), expected);
  }

  private VectorSearchQuery getVectorQuery(List<MqlFilterOperator> operators) {
    return VectorQueryBuilder.builder()
        .index("test")
        .criteria(
            ApproximateVectorQueryCriteriaBuilder.builder()
                .path(FieldPath.parse("vector"))
                .numCandidates(20)
                .limit(10)
                .queryVector(Vector.fromFloats(new float[] {1f, 2f, 3f}, NATIVE))
                .filter(
                    new VectorSearchFilter.ClauseFilter(
                        ClauseBuilder.simpleClause()
                            .path(FieldPath.parse(FILTER_PATH))
                            .operators(operators)
                            .build()))
                .build())
        .build();
  }

  private LuceneVectorTranslation getLuceneVectorTranslation() {
    return new LuceneVectorTranslation(
        List.of(
            VectorDataFieldDefinitionBuilder.builder()
                .path(FieldPath.parse(VECTOR_PATH))
                .numDimensions(3)
                .similarity(VectorSimilarity.EUCLIDEAN)
                .quantization(VectorQuantization.NONE)
                .build(),
            VectorIndexFilterFieldDefinition.create(FieldPath.parse(FILTER_PATH))));
  }

  private static Query createLongLuceneRangeQueries(long lowerBound, long upperBound) {
    return BooleanComposer.constantScoreDisjunction(
        new IndexOrDocValuesQuery(
            org.apache.lucene.document.LongPoint.newRangeQuery(
                "$type:int64/" + FILTER_PATH, lowerBound, upperBound),
            NumericDocValuesField.newSlowRangeQuery(
                "$type:int64/" + FILTER_PATH, lowerBound, upperBound)),
        org.apache.lucene.document.LongPoint.newRangeQuery(
            "$type:int64Multiple/" + FILTER_PATH, lowerBound, upperBound));
  }

  private static Query createDoubleLuceneRangeQueries(double lowerBound, double upperBound) {
    long convertedLowerBound = LuceneDoubleConversionUtils.toLong(lowerBound);
    long convertedUpperBound = LuceneDoubleConversionUtils.toLong(upperBound);
    return BooleanComposer.constantScoreDisjunction(
        new IndexOrDocValuesQuery(
            org.apache.lucene.document.LongPoint.newRangeQuery(
                "$type:double/" + FILTER_PATH, convertedLowerBound, convertedUpperBound),
            NumericDocValuesField.newSlowRangeQuery(
                "$type:double/" + FILTER_PATH, convertedLowerBound, convertedUpperBound)),
        org.apache.lucene.document.LongPoint.newRangeQuery(
            "$type:doubleMultiple/" + FILTER_PATH, convertedLowerBound, convertedUpperBound));
  }
}
