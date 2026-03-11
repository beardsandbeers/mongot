package com.xgen.mongot.index.lucene.query;

import static com.xgen.mongot.util.bson.FloatVector.OriginalType.NATIVE;

import com.xgen.mongot.featureflag.Feature;
import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.definition.VectorIndexFilterFieldDefinition;
import com.xgen.mongot.index.definition.VectorQuantization;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.lucene.query.custom.MongotKnnFloatQuery;
import com.xgen.mongot.index.lucene.util.AnalyzedText;
import com.xgen.mongot.index.lucene.util.LuceneDoubleConversionUtils;
import com.xgen.mongot.index.query.VectorSearchQuery;
import com.xgen.mongot.index.query.operators.VectorSearchFilter;
import com.xgen.mongot.index.query.operators.mql.ComparisonOperator;
import com.xgen.mongot.index.query.operators.value.NonNullValue;
import com.xgen.mongot.index.query.points.DatePoint;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.Vector;
import com.xgen.testing.mongot.index.definition.VectorDataFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.query.ApproximateVectorQueryCriteriaBuilder;
import com.xgen.testing.mongot.index.query.VectorQueryBuilder;
import com.xgen.testing.mongot.index.query.operators.mql.ClauseBuilder;
import com.xgen.testing.mongot.index.query.operators.mql.MqlFilterOperatorBuilder;
import com.xgen.testing.mongot.index.query.operators.mql.ValueBuilder;
import com.xgen.testing.mongot.mock.index.SearchIndex;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.bson.types.ObjectId;
import org.junit.Test;

public class InVectorQueryFactoryTest {

  private static final IndexMetricsUpdater.QueryingMetricsUpdater metrics =
      new IndexMetricsUpdater.QueryingMetricsUpdater(SearchIndex.mockMetricsFactory());

  private static final String FILTER_PATH = "a";
  private static final String VECTOR_PATH = "vector";
  private static final FeatureFlags DEFAULT_FEATURE_SET = FeatureFlags.getDefault();
  private static final FeatureFlags INDEX_OR_DOC_VALUES_QUERY_FEATURE_SET =
      FeatureFlags.withDefaults()
          .enable(Feature.INDEX_OR_DOC_VALUES_QUERY_FOR_IN_OPERATOR)
          .enable(Feature.NEW_EMBEDDED_SEARCH_CAPABILITIES)
          .build();

  @Test
  public void testBoolean() throws Exception {
    var operator =
        MqlFilterOperatorBuilder.in()
            .values(List.of(ValueBuilder.bool(true), ValueBuilder.bool(false)))
            .build();

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new BooleanQuery.Builder()
                .add(
                    new ConstantScoreQuery(
                        new BooleanQuery.Builder()
                            .add(
                                new TermQuery(new Term("$type:boolean/a", "T")),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                new TermQuery(new Term("$type:boolean/a", "F")),
                                BooleanClause.Occur.SHOULD)
                            .build()),
                    BooleanClause.Occur.MUST)
                .build());

    getLuceneVectorTranslation(DEFAULT_FEATURE_SET)
        .assertTranslatedTo(getVectorQuery(operator), expected);
  }

  @Test
  public void testObjectId() throws Exception {
    var objectIds =
        List.of(
            ValueBuilder.objectId(new ObjectId("507f1f77bcf86cd799439011")),
            ValueBuilder.objectId(new ObjectId("507f1f77bcf86cd799439012")));
    var operator = MqlFilterOperatorBuilder.in().values(List.copyOf(objectIds)).build();

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new BooleanQuery.Builder()
                .add(
                    new ConstantScoreQuery(
                        new BooleanQuery.Builder()
                            .add(
                                new TermQuery(
                                    new Term(
                                        "$type:objectId/a",
                                        new BytesRef(objectIds.get(0).value().toByteArray()))),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                new TermQuery(
                                    new Term(
                                        "$type:objectId/a",
                                        new BytesRef(objectIds.get(1).value().toByteArray()))),
                                BooleanClause.Occur.SHOULD)
                            .build()),
                    BooleanClause.Occur.MUST)
                .build());

    getLuceneVectorTranslation(DEFAULT_FEATURE_SET)
        .assertTranslatedTo(getVectorQuery(operator), expected);
  }

  @Test
  public void testLong() throws Exception {

    List<Long> longs = List.of(1L, 2L);
    long[] longArr = longs.stream().mapToLong(Long::longValue).toArray();
    List<Long> doubles =
        longs.stream().map(LuceneDoubleConversionUtils::toLong).collect(Collectors.toList());
    long[] doubleArr = doubles.stream().mapToLong(Long::longValue).toArray();
    List<NonNullValue> longValues =
        List.of(ValueBuilder.longNumber(1L), ValueBuilder.longNumber(2L));

    var operator = MqlFilterOperatorBuilder.in().values(longValues).build();

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new BooleanQuery.Builder()
                .add(
                    new ConstantScoreQuery(
                        new BooleanQuery.Builder()
                            .add(
                                LongPoint.newSetQuery("$type:int64/a", longArr),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:int64Multiple/a", longs),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:double/a", doubleArr),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:doubleMultiple/a", doubles),
                                BooleanClause.Occur.SHOULD)
                            .build()),
                    BooleanClause.Occur.MUST)
                .build());

    getLuceneVectorTranslation(DEFAULT_FEATURE_SET)
        .assertTranslatedTo(getVectorQuery(operator), expected);
  }

  @Test
  public void testIndexOrDocValuesQueryLong() throws Exception {

    List<Long> longs = List.of(1L, 2L);
    long[] longArr = longs.stream().mapToLong(Long::longValue).toArray();
    List<Long> doubles =
        longs.stream().map(LuceneDoubleConversionUtils::toLong).collect(Collectors.toList());
    long[] doubleArr = doubles.stream().mapToLong(Long::longValue).toArray();
    List<NonNullValue> longValues =
        List.of(ValueBuilder.longNumber(1L), ValueBuilder.longNumber(2L));

    var operator = MqlFilterOperatorBuilder.in().values(longValues).build();

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new BooleanQuery.Builder()
                .add(
                    new ConstantScoreQuery(
                        new BooleanQuery.Builder()
                            .add(
                                LongField.newSetQuery("$type:int64/a", longArr),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:int64Multiple/a", longs),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongField.newSetQuery("$type:double/a", doubleArr),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:doubleMultiple/a", doubles),
                                BooleanClause.Occur.SHOULD)
                            .build()),
                    BooleanClause.Occur.MUST)
                .build());

    getLuceneVectorTranslation(INDEX_OR_DOC_VALUES_QUERY_FEATURE_SET)
        .assertTranslatedTo(getVectorQuery(operator), expected);
  }

  @Test
  public void testDouble() throws Exception {

    List<Long> doubles =
        Stream.of(1.123, 2.345)
            .map(LuceneDoubleConversionUtils::toLong)
            .collect(Collectors.toList());
    List<Long> longs = Stream.of(1.123, 2.345).map(Double::longValue).collect(Collectors.toList());

    List<NonNullValue> doubleValues =
        List.of(ValueBuilder.doubleNumber(1.123), ValueBuilder.doubleNumber(2.345));

    long[] longArr = longs.stream().mapToLong(Long::longValue).toArray();
    long[] doubleArr = doubles.stream().mapToLong(Long::longValue).toArray();

    var operator = MqlFilterOperatorBuilder.in().values(doubleValues).build();

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new BooleanQuery.Builder()
                .add(
                    new ConstantScoreQuery(
                        new BooleanQuery.Builder()
                            .add(
                                LongPoint.newSetQuery("$type:int64/a", longArr),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:int64Multiple/a", longs),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:double/a", doubleArr),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:doubleMultiple/a", doubles),
                                BooleanClause.Occur.SHOULD)
                            .build()),
                    BooleanClause.Occur.MUST)
                .build());

    getLuceneVectorTranslation(DEFAULT_FEATURE_SET)
        .assertTranslatedTo(getVectorQuery(operator), expected);
  }

  @Test
  public void testIndexOrDocValuesQueryDouble() throws Exception {

    List<Long> doubles =
        Stream.of(1.123, 2.345)
            .map(LuceneDoubleConversionUtils::toLong)
            .collect(Collectors.toList());
    List<Long> longs = Stream.of(1.123, 2.345).map(Double::longValue).collect(Collectors.toList());

    List<NonNullValue> doubleValues =
        List.of(ValueBuilder.doubleNumber(1.123), ValueBuilder.doubleNumber(2.345));

    long[] longArr = longs.stream().mapToLong(Long::longValue).toArray();
    long[] doubleArr = doubles.stream().mapToLong(Long::longValue).toArray();

    var operator = MqlFilterOperatorBuilder.in().values(doubleValues).build();

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new BooleanQuery.Builder()
                .add(
                    new ConstantScoreQuery(
                        new BooleanQuery.Builder()
                            .add(
                                LongField.newSetQuery("$type:int64/a", longArr),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:int64Multiple/a", longs),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongField.newSetQuery("$type:double/a", doubleArr),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:doubleMultiple/a", doubles),
                                BooleanClause.Occur.SHOULD)
                            .build()),
                    BooleanClause.Occur.MUST)
                .build());

    getLuceneVectorTranslation(INDEX_OR_DOC_VALUES_QUERY_FEATURE_SET)
        .assertTranslatedTo(getVectorQuery(operator), expected);
  }

  @Test
  public void testDate() throws Exception {
    var dates = List.of(new Date(1), new Date(2), new Date(3));
    var translatedDates = dates.stream().map(Date::getTime).collect(Collectors.toList());
    List<NonNullValue> dateValues =
        dates.stream()
            .map(date -> ValueBuilder.date(new DatePoint(date)))
            .collect(Collectors.toList());

    var operator = MqlFilterOperatorBuilder.in().values(dateValues).build();
    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new BooleanQuery.Builder()
                .add(
                    new ConstantScoreQuery(
                        new BooleanQuery.Builder()
                            .add(
                                LongPoint.newSetQuery("$type:date/a", translatedDates),
                                BooleanClause.Occur.SHOULD)
                            .add(
                                LongPoint.newSetQuery("$type:dateMultiple/a", translatedDates),
                                BooleanClause.Occur.SHOULD)
                            .build()),
                    BooleanClause.Occur.MUST)
                .build());

    getLuceneVectorTranslation(DEFAULT_FEATURE_SET)
        .assertTranslatedTo(getVectorQuery(operator), expected);
  }

  @Test
  public void testToken() throws Exception {
    var strings = List.of("a", "b", "c");
    List<NonNullValue> stringValues =
        strings.stream().map(ValueBuilder::string).collect(Collectors.toList());
    var field = "$type:token/a";

    List<BytesRef> analyzedStrings =
        strings.stream()
            .map(
                value -> {
                  try {
                    return AnalyzedText.applyTokenFieldTypeNormalizer(
                        field, new StandardAnalyzer(), value);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                })
            .map(BytesRef::new)
            .collect(Collectors.toList());

    var operator = MqlFilterOperatorBuilder.in().values(stringValues).build();

    var expected =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            new BooleanQuery.Builder()
                .add(
                    new IndexOrDocValuesQuery(
                        new TermInSetQuery(field, analyzedStrings),
                        SortedSetDocValuesField.newSlowSetQuery(field, analyzedStrings)),
                    BooleanClause.Occur.MUST)
                .build());

    getLuceneVectorTranslation(DEFAULT_FEATURE_SET)
        .assertTranslatedTo(getVectorQuery(operator), expected);
  }

  private VectorSearchQuery getVectorQuery(ComparisonOperator operator) {
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
                            .addOperator(operator)
                            .build()))
                .build())
        .build();
  }

  private LuceneVectorTranslation getLuceneVectorTranslation(FeatureFlags featureFlags) {
    return new LuceneVectorTranslation(
        List.of(
            VectorDataFieldDefinitionBuilder.builder()
                .path(FieldPath.parse(VECTOR_PATH))
                .numDimensions(3)
                .similarity(VectorSimilarity.EUCLIDEAN)
                .quantization(VectorQuantization.NONE)
                .build(),
            VectorIndexFilterFieldDefinition.create(FieldPath.parse(FILTER_PATH))),
        featureFlags);
  }
}
