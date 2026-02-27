package com.xgen.mongot.index.query;

import com.xgen.mongot.index.definition.VectorFieldDefinitionResolver;
import com.xgen.mongot.index.definition.VectorIndexFieldDefinition;
import com.xgen.mongot.index.definition.VectorQuantization;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.util.FieldPath;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.definition.VectorDataFieldDefinitionBuilder;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class VectorQueryTimeMappingChecksTest {

  @Test
  public void validKnnVectorFieldShouldPassValidation() throws InvalidQueryException {
    var resolver = Mockito.mock(VectorFieldDefinitionResolver.class);
    var checks = new VectorQueryTimeMappingChecks(resolver);
    var path = FieldPath.newRoot("foo");
    var definition =
        VectorDataFieldDefinitionBuilder.builder()
            .path(path)
            .numDimensions(100)
            .similarity(VectorSimilarity.EUCLIDEAN)
            .quantization(VectorQuantization.NONE)
            .build();

    Mockito.when(resolver.getVectorFieldSpecification(path))
        .thenReturn(Optional.of(definition.specification()));
    checks.validateVectorField(path, Optional.empty(), 100);
  }

  @Test(expected = InvalidQueryException.class)
  public void shouldFailKnnVectorValidationOnDimensionMismatch() throws InvalidQueryException {
    var resolver = Mockito.mock(VectorFieldDefinitionResolver.class);
    var checks = new VectorQueryTimeMappingChecks(resolver);
    var path = FieldPath.newRoot("foo");
    var definition =
        VectorDataFieldDefinitionBuilder.builder()
            .path(path)
            .numDimensions(100)
            .similarity(VectorSimilarity.EUCLIDEAN)
            .quantization(VectorQuantization.NONE)
            .build();

    Mockito.when(resolver.getVectorFieldSpecification(path))
        .thenReturn(Optional.of(definition.specification()));

    TestUtils.assertThrows(
        "vector field is indexed with 100 dimensions but queried with 999",
        InvalidQueryException.class,
        () -> checks.validateVectorField(path, Optional.empty(), 999));

    checks.validateVectorField(path, Optional.empty(), 999);
  }

  @Test(expected = InvalidQueryException.class)
  public void shouldFailKnnVectorValidationWhenPathIsNotIndexedAsVector()
      throws InvalidQueryException {
    var resolver = Mockito.mock(VectorFieldDefinitionResolver.class);
    var checks = new VectorQueryTimeMappingChecks(resolver);
    var path = FieldPath.newRoot("foo");

    Mockito.when(resolver.getVectorFieldSpecification(path)).thenReturn(Optional.empty());

    TestUtils.assertThrows(
        String.format("%s is not indexed as vector", path),
        InvalidQueryException.class,
        () -> checks.validateVectorField(path, Optional.empty(), 100));

    checks.validateVectorField(path, Optional.empty(), 100);
  }

  @Test
  public void shouldReturnTrueWhenPathIsIndexedAsFilter() {
    var resolver = Mockito.mock(VectorFieldDefinitionResolver.class);
    var checks = new VectorQueryTimeMappingChecks(resolver);
    var path = FieldPath.newRoot("foo");

    Mockito.when(resolver.isIndexed(path, Optional.empty(), VectorIndexFieldDefinition.Type.FILTER))
        .thenReturn(true);
    Assert.assertTrue(checks.indexedAsDate(path, Optional.empty()));
    Assert.assertTrue(checks.indexedAsNumber(path, Optional.empty()));
    Assert.assertTrue(checks.indexedAsBoolean(path, Optional.empty()));
    Assert.assertTrue(checks.indexedAsToken(path, Optional.empty()));
    Assert.assertTrue(checks.indexedAsObjectId(path, Optional.empty()));
  }

  @Test
  public void shouldReturnFalseWhenPathIsNotIndexedAsFilter() {
    var resolver = Mockito.mock(VectorFieldDefinitionResolver.class);
    var checks = new VectorQueryTimeMappingChecks(resolver);
    var path = FieldPath.newRoot("foo");

    Mockito.when(resolver.isIndexed(path, Optional.empty(), VectorIndexFieldDefinition.Type.FILTER))
        .thenReturn(false);
    Assert.assertFalse(checks.indexedAsDate(path, Optional.empty()));
    Assert.assertFalse(checks.indexedAsNumber(path, Optional.empty()));
    Assert.assertFalse(checks.indexedAsBoolean(path, Optional.empty()));
    Assert.assertFalse(checks.indexedAsToken(path, Optional.empty()));
    Assert.assertFalse(checks.indexedAsObjectId(path, Optional.empty()));
  }
}
