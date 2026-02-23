package com.xgen.mongot.index;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xgen.mongot.index.definition.SearchIndexDefinitionGeneration;
import com.xgen.mongot.index.definition.VectorIndexDefinitionGeneration;
import com.xgen.mongot.index.version.Generation;
import com.xgen.testing.mongot.index.definition.AnalyzerBoundSearchIndexDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.SearchIndexDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.VectorIndexDefinitionBuilder;
import org.junit.Assert;
import org.junit.Test;

public class IndexGenerationTest {

  @Test
  public void testThrowsWhenIndexIsIncompatibleWithDefinition() {
    var index = mock(Index.class);
    when(index.isCompatibleWith(any())).thenReturn(false);
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            new IndexGeneration(
                index,
                new VectorIndexDefinitionGeneration(
                    VectorIndexDefinitionBuilder.builder()
                        .withCosineVectorField("path", 1024)
                        .build(),
                    Generation.FIRST)));
  }

  @Test
  public void testDoesNotThrowWhenIndexIsCompatibleWithDefinition() {
    var index = mock(Index.class);
    when(index.isCompatibleWith(any())).thenReturn(true);
    new IndexGeneration(
        index,
        new SearchIndexDefinitionGeneration(
            AnalyzerBoundSearchIndexDefinitionBuilder.builder()
                .index(SearchIndexDefinitionBuilder.VALID_INDEX)
                .build(),
            Generation.FIRST));
  }
}
