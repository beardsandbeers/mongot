package com.xgen.mongot.index.lucene;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xgen.mongot.index.definition.TokenFieldDefinition;
import com.xgen.mongot.index.lucene.facet.TokenFacetsStateCache;
import com.xgen.mongot.index.lucene.facet.TokenSsdvFacetState;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.query.InvalidQueryException;
import com.xgen.mongot.index.query.collectors.FacetCollector;
import com.xgen.mongot.index.query.collectors.FacetDefinition;
import com.xgen.mongot.util.FieldPath;
import com.xgen.testing.mongot.index.query.collectors.FacetCollectorBuilder;
import com.xgen.testing.mongot.index.query.operators.OperatorBuilder;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.MultiFacets;
import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.bson.BsonDateTime;
import org.bson.BsonDouble;
import org.bson.BsonValue;
import org.junit.Before;
import org.junit.Test;

public class MongotDrillSidewaysTest {
  private static final String STRING_FACET_NAME = "stringFacet";
  private static final String NUMERIC_FACET_NAME = "numericFacet";
  private static final String DATE_FACET_NAME = "dateFacet";

  private static final String NUMERIC_FACET_PATH = "numericFacetPath";
  private static final String DATE_FACET_PATH = "dateFacetPath";
  private static final String STRING_FACET_PATH = "stringFacetPath";

  private IndexSearcher searcher;
  private FacetsConfig config;
  private FacetCollector collector;
  private LuceneFacetContext facetContext;
  private FieldPath returnScope;
  private SortedSetDocValuesReaderState facetsStateCache;
  private TokenFacetsStateCache tokenFacetsStateCache;

  private Map<String, FacetDefinition> facetDefinitions;
  private MongotDrillSideways mongotDrillSideways;

  @Before
  public void setUp() {
    this.searcher = mock(IndexSearcher.class);
    this.config = mock(FacetsConfig.class);
    this.facetsStateCache = mock(SortedSetDocValuesReaderState.class);
    this.tokenFacetsStateCache = mock(TokenFacetsStateCache.class);
    this.facetContext = mock(LuceneFacetContext.class);
    this.returnScope = mock(FieldPath.class);

    this.facetDefinitions = new HashMap<>();
    this.collector =
        FacetCollectorBuilder.facet()
            .operator(OperatorBuilder.exists().path("_id").build())
            .facetDefinitions(this.facetDefinitions)
            .build();

    this.mongotDrillSideways =
        new MongotDrillSideways(
            this.searcher,
            this.config,
            this.collector,
            this.facetContext,
            Optional.of(this.returnScope),
            Optional.of(this.facetsStateCache),
            Optional.of(this.tokenFacetsStateCache),
            Optional.empty());
  }

  @Test
  public void testNoDimensionsWithEmptyCollectors() throws IOException {
    Facets result =
        this.mongotDrillSideways.buildFacetsResult(new FacetsCollector[] {}, new String[] {});
    validateFacetsResult(result, null);
  }

  @Test
  public void testBuildFacetsResultToken() throws Exception {
    when(this.facetContext.getStringFacetFieldDefinition(any(), any()))
        .thenReturn(new TokenFieldDefinition(Optional.empty()));

    var stringFacetDefinition = getStringFacetDefinition();
    var luceneDim =
        FieldName.TypeField.TOKEN.getLuceneFieldName(
            FieldPath.parse(stringFacetDefinition.path()), Optional.of(this.returnScope));

    // Structured mock setup for TokenSsdvFacetState
    TokenSsdvFacetState tokenSsdvFacetState = mock(TokenSsdvFacetState.class);

    // Mock sorted set doc values
    SortedSetDocValues sortedSetDocValues = mock(SortedSetDocValues.class);
    when(sortedSetDocValues.getValueCount()).thenReturn(10L); // Simulate non-zero doc values

    // Make TokenSsdvFacetState return mocked SortedSetDocValues
    when(tokenSsdvFacetState.getDocValues()).thenReturn(sortedSetDocValues);
    when(tokenSsdvFacetState.getField()).thenReturn(luceneDim);

    // Mock TokenFacetsStateCache behavior
    when(this.tokenFacetsStateCache.get(eq(luceneDim)))
        .thenReturn(Optional.of(tokenSsdvFacetState));

    // Add facet definition
    addFacetDefinition(stringFacetDefinition, STRING_FACET_NAME);

    // Mock FacetsCollector array
    FacetsCollector[] drillSidewaysCollectors = {mock(FacetsCollector.class)};
    String[] drillSidewaysDims = {STRING_FACET_NAME};

    Facets result =
        this.mongotDrillSideways.buildFacetsResult(drillSidewaysCollectors, drillSidewaysDims);

    assertNotNull(result);
    assertTrue(result instanceof MultiFacets);

    MultiFacets multiFacets = (MultiFacets) result;

    // Ensure one dimension exists in the facets
    List<FacetResult> facetResults = multiFacets.getAllDims(10); // Get topN dimensions
    assertEquals(1, facetResults.size());

    FacetResult stringFacetResult = facetResults.get(0);
    assertNotNull(stringFacetResult);
    assertEquals(luceneDim, stringFacetResult.dim);
  }

  @Test
  public void testNumericFacetProcessing() throws IOException, InvalidQueryException {
    mockFacetContext(
        NUMERIC_FACET_PATH, createNumericRanges(), FacetDefinition.NumericFacetDefinition.class);
    addFacetDefinition(getNumericFacetDefinition(), NUMERIC_FACET_NAME);

    FacetsCollector[] drillSidewaysCollectors = {mock(FacetsCollector.class)};
    String[] sidewaysDims = {NUMERIC_FACET_NAME};

    Facets result =
        this.mongotDrillSideways.buildFacetsResult(drillSidewaysCollectors, sidewaysDims);

    validateFacetsResult(result, NUMERIC_FACET_PATH);
  }

  @Test
  public void testDateFacetProcessing() throws IOException, InvalidQueryException {
    mockFacetContext(
        DATE_FACET_PATH, createDateRanges(), FacetDefinition.DateFacetDefinition.class);
    addFacetDefinition(getDateFacetDefinition(), DATE_FACET_NAME);

    FacetsCollector[] drillSidewaysCollectors = {mock(FacetsCollector.class)};
    String[] sidewaysDims = {DATE_FACET_NAME};

    Facets result =
        this.mongotDrillSideways.buildFacetsResult(drillSidewaysCollectors, sidewaysDims);

    validateFacetsResult(result, DATE_FACET_PATH);
  }

  // Helper Methods (Placed at Bottom for Organization)
  private void validateFacetsResult(Facets result, String expectedPath) throws IOException {
    assertNotNull(result);
    assertTrue(result instanceof MultiFacets);

    MultiFacets multiFacets = (MultiFacets) result;
    List<FacetResult> facetResults = multiFacets.getAllDims(10);
    assertEquals(expectedPath == null ? 0 : 1, facetResults.size());

    if (expectedPath != null) {
      FacetResult facetResult = facetResults.get(0);
      assertNotNull(facetResult);
      assertEquals(expectedPath, facetResult.dim);
    }
  }

  private LongRange[] createNumericRanges() {
    return new LongRange[] {
      new LongRange("Range 1", 0L, true, 10L, true),
      new LongRange("Range 2", 10L, true, 20L, true),
      new LongRange("Range 3", 20L, true, 30L, true)
    };
  }

  private LongRange[] createDateRanges() {
    return new LongRange[] {
      new LongRange("Range 1", 1667750400000L, true, 1670438800000L, true),
      new LongRange("Range 2", 1670438800000L, true, 1673030800000L, true),
      new LongRange("Range 3", 1673030800000L, true, 1675718000000L, true)
    };
  }

  private void mockFacetContext(
      String path,
      LongRange[] ranges,
      Class<? extends FacetDefinition.BoundaryFacetDefinition<? extends BsonValue>>
          facetDefinitionClass)
      throws InvalidQueryException {
    when(this.facetContext.getBoundaryFacetPath(any(facetDefinitionClass), any())).thenReturn(path);
    when(this.facetContext.getRanges(any(facetDefinitionClass), any())).thenReturn(ranges);
  }

  private void addFacetDefinition(FacetDefinition definition, String name) {
    this.facetDefinitions.put(name, definition);
  }

  private FacetDefinition.NumericFacetDefinition getNumericFacetDefinition() {
    return new FacetDefinition.NumericFacetDefinition(
        NUMERIC_FACET_PATH,
        Optional.empty(),
        List.of(
            new BsonDouble(1.0), new BsonDouble(2.0), new BsonDouble(3.0), new BsonDouble(4.0)));
  }

  private FacetDefinition.DateFacetDefinition getDateFacetDefinition() {
    return new FacetDefinition.DateFacetDefinition(
        DATE_FACET_PATH,
        Optional.empty(),
        List.of(
            new BsonDateTime(1L),
            new BsonDateTime(2L),
            new BsonDateTime(3L),
            new BsonDateTime(4L),
            new BsonDateTime(5L)));
  }

  private FacetDefinition.StringFacetDefinition getStringFacetDefinition() {
    return new FacetDefinition.StringFacetDefinition(STRING_FACET_PATH, 4);
  }
}
