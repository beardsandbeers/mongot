package com.xgen.mongot.index.lucene.query;

import static com.xgen.mongot.util.bson.FloatVector.OriginalType.NATIVE;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.mockito.Mockito.mock;

import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.definition.VectorIndexFilterFieldDefinition;
import com.xgen.mongot.index.definition.VectorQuantization;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.lucene.query.context.VectorQueryFactoryContext;
import com.xgen.mongot.index.lucene.query.custom.MongotKnnFloatQuery;
import com.xgen.mongot.index.query.InvalidQueryException;
import com.xgen.mongot.index.query.operators.VectorSearchFilter;
import com.xgen.mongot.index.query.operators.bound.BooleanRangeBound;
import com.xgen.mongot.index.query.operators.mql.MqlFilterOperator;
import com.xgen.mongot.index.query.points.BooleanPoint;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.Vector;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.testing.mongot.index.definition.VectorDataFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.query.ApproximateVectorQueryCriteriaBuilder;
import com.xgen.testing.mongot.index.query.VectorQueryBuilder;
import com.xgen.testing.mongot.index.query.operators.mql.ClauseBuilder;
import com.xgen.testing.mongot.index.query.operators.mql.MqlFilterOperatorBuilder;
import com.xgen.testing.mongot.index.query.operators.mql.ValueBuilder;
import com.xgen.testing.mongot.mock.index.SearchIndex;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class BooleanRangeVectorQueryFactoryTest {

  private static final IndexMetricsUpdater.QueryingMetricsUpdater metrics =
      new IndexMetricsUpdater.QueryingMetricsUpdater(SearchIndex.mockMetricsFactory());

  private static final BooleanPoint TRUE = new BooleanPoint(true);
  private static final BooleanPoint FALSE = new BooleanPoint(false);
  private static final String VECTOR_PATH = "vector";
  private static final String FILTER_PATH = "a";
  private final List<MqlFilterOperator> operators;
  private final BooleanRangeBound rangeBound;

  public BooleanRangeVectorQueryFactoryTest(
      List<MqlFilterOperator> operators, BooleanRangeBound rangeBound) {
    this.operators = operators;
    this.rangeBound = rangeBound;
  }

  /** Test data. */
  @Parameterized.Parameters(name = "{0}: {1}")
  public static Collection<Object[]> data() throws BsonParseException {
    return Arrays.asList(
        new Object[][] {
          {
            List.of(MqlFilterOperatorBuilder.gt().value(ValueBuilder.bool(false)).build()),
            new BooleanRangeBound(of(FALSE), empty(), false, false)
          },
          {
            List.of(MqlFilterOperatorBuilder.gte().value(ValueBuilder.bool(false)).build()),
            new BooleanRangeBound(of(FALSE), empty(), true, false)
          },
          {
            List.of(MqlFilterOperatorBuilder.gt().value(ValueBuilder.bool(true)).build()),
            new BooleanRangeBound(of(TRUE), empty(), false, false)
          },
          {
            List.of(MqlFilterOperatorBuilder.gte().value(ValueBuilder.bool(true)).build()),
            new BooleanRangeBound(of(TRUE), empty(), true, false)
          },
          {
            List.of(MqlFilterOperatorBuilder.lt().value(ValueBuilder.bool(false)).build()),
            new BooleanRangeBound(empty(), of(FALSE), false, false)
          },
          {
            List.of(MqlFilterOperatorBuilder.lte().value(ValueBuilder.bool(false)).build()),
            new BooleanRangeBound(empty(), of(FALSE), false, true)
          },
          {
            List.of(MqlFilterOperatorBuilder.lt().value(ValueBuilder.bool(true)).build()),
            new BooleanRangeBound(empty(), of(TRUE), false, false)
          },
          {
            List.of(MqlFilterOperatorBuilder.lte().value(ValueBuilder.bool(true)).build()),
            new BooleanRangeBound(empty(), of(TRUE), false, true)
          },
          {
            List.of(
                MqlFilterOperatorBuilder.gte().value(ValueBuilder.bool(false)).build(),
                MqlFilterOperatorBuilder.lte().value(ValueBuilder.bool(true)).build()),
            new BooleanRangeBound(of(FALSE), of(TRUE), true, true)
          },
          {
            List.of(
                MqlFilterOperatorBuilder.gte().value(ValueBuilder.bool(false)).build(),
                MqlFilterOperatorBuilder.lt().value(ValueBuilder.bool(true)).build()),
            new BooleanRangeBound(of(FALSE), of(TRUE), true, false)
          },
          {
            List.of(
                MqlFilterOperatorBuilder.gt().value(ValueBuilder.bool(false)).build(),
                MqlFilterOperatorBuilder.lt().value(ValueBuilder.bool(true)).build()),
            new BooleanRangeBound(of(FALSE), of(TRUE), false, false)
          },
          {
            List.of(
                MqlFilterOperatorBuilder.gt().value(ValueBuilder.bool(false)).build(),
                MqlFilterOperatorBuilder.lte().value(ValueBuilder.bool(true)).build()),
            new BooleanRangeBound(of(FALSE), of(TRUE), false, true)
          },
          {
            List.of(
                MqlFilterOperatorBuilder.gte().value(ValueBuilder.bool(true)).build(),
                MqlFilterOperatorBuilder.lte().value(ValueBuilder.bool(false)).build()),
            new BooleanRangeBound(of(TRUE), of(FALSE), true, true),
          }
        });
  }

  @Test
  public void testBooleanRange() throws IOException, InvalidQueryException {
    var query =
        VectorQueryBuilder.builder()
            .index("test")
            .criteria(
                ApproximateVectorQueryCriteriaBuilder.builder()
                    .path(FieldPath.parse(VECTOR_PATH))
                    .numCandidates(20)
                    .limit(10)
                    .queryVector(Vector.fromFloats(new float[] {1f, 2f, 3f}, NATIVE))
                    .filter(
                        new VectorSearchFilter.ClauseFilter(
                            ClauseBuilder.simpleClause()
                                .path(FieldPath.parse(FILTER_PATH))
                                .operators(this.operators)
                                .build()))
                    .build())
            .build();

    var translatedQuery =
        new LuceneVectorTranslation(
                List.of(
                    VectorDataFieldDefinitionBuilder.builder()
                        .path(FieldPath.parse(VECTOR_PATH))
                        .numDimensions(3)
                        .similarity(VectorSimilarity.EUCLIDEAN)
                        .quantization(VectorQuantization.NONE)
                        .build(),
                    VectorIndexFilterFieldDefinition.create(FieldPath.parse(FILTER_PATH))))
            .translate(query);

    Query rangeBoundQuery;

    if (this.rangeBound.getLower().isPresent() && this.rangeBound.getUpper().isPresent()) {
      // break the range into two, because we build our vector filter like that, range queries for
      // the two operators are built separately and the clubbed together
      var lower = this.rangeBound.getLower();
      boolean lowerInclusive = this.rangeBound.lowerInclusive();
      var upper = this.rangeBound.getUpper();
      boolean upperInclusive = this.rangeBound.upperInclusive();

      var rangeBound1 = new BooleanRangeBound(lower, empty(), lowerInclusive, false);
      var rangeBound2 = new BooleanRangeBound(empty(), upper, false, upperInclusive);

      rangeBoundQuery =
          new BooleanQuery.Builder()
              .add(getRangeBoundQuery(rangeBound1), BooleanClause.Occur.MUST)
              .add(getRangeBoundQuery(rangeBound2), BooleanClause.Occur.MUST)
              .build();
    } else {
      rangeBoundQuery =
          new BooleanQuery.Builder()
              .add(getRangeBoundQuery(this.rangeBound), BooleanClause.Occur.MUST)
              .build();
    }

    var expectedLuceneVectorQuery =
        new MongotKnnFloatQuery(
            metrics,
            FeatureFlags.getDefault(),
            "$type:knnVector/vector",
            new float[] {1, 2, 3},
            20,
            rangeBoundQuery);

    Assert.assertEquals(expectedLuceneVectorQuery, translatedQuery);
  }

  private Query getRangeBoundQuery(BooleanRangeBound rangeBound) throws InvalidQueryException {
    return new BooleanRangeQueryFactory(
            new EqualsQueryFactory(mock(VectorQueryFactoryContext.class)))
        .fromBounds(rangeBound)
        .apply(FieldPath.parse(FILTER_PATH), empty());
  }
}
