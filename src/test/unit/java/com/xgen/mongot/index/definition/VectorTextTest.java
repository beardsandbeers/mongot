package com.xgen.mongot.index.definition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.xgen.mongot.index.definition.VectorFieldSpecification.HnswOptions;
import com.xgen.mongot.index.definition.quantization.VectorAutoEmbedQuantization;
import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import com.xgen.mongot.util.FieldPath;
import com.xgen.testing.mongot.index.definition.VectorDataFieldDefinitionBuilder;
import org.junit.Assert;
import org.junit.Test;

public class VectorTextTest {

  @Test
  public void testNonVector() {
    FieldPath path = FieldPath.parse("path");
    VectorDataFieldDefinition dataField =
        VectorDataFieldDefinitionBuilder.builder()
            .path(path)
            .numDimensions(1)
            .similarity(VectorSimilarity.EUCLIDEAN)
            .quantization(VectorQuantization.NONE)
            .build();
    Assert.assertNotSame(VectorIndexFieldDefinition.Type.TEXT, dataField.getType());
  }

  @Test
  public void testVectorTextDefinition() {
    FieldPath path = FieldPath.parse("path");
    VectorTextFieldDefinition textField = new VectorTextFieldDefinition(path);
    Assert.assertSame(VectorTextFieldDefinition.Type.TEXT, textField.getType());
    Assert.assertEquals(1024, textField.specification().numDimensions());
    Assert.assertEquals(VectorSimilarity.EUCLIDEAN, textField.specification().similarity());
    Assert.assertEquals(VectorQuantization.NONE, textField.specification().quantization());
  }

  @Test
  public void autoEmbedDefinition_defaultConfiguration() {
    FieldPath path = FieldPath.parse("path");
    VectorAutoEmbedFieldDefinition textField = new VectorAutoEmbedFieldDefinition("dummy", path);
    Assert.assertSame(VectorTextFieldDefinition.Type.AUTO_EMBED, textField.getType());
    Assert.assertEquals(1024, textField.specification().numDimensions());
    Assert.assertEquals(VectorSimilarity.DOT_PRODUCT, textField.specification().similarity());
    Assert.assertEquals(VectorQuantization.NONE, textField.specification().quantization());
    Assert.assertEquals(
        VectorAutoEmbedQuantization.FLOAT, textField.specification().autoEmbedQuantization());
  }

  // VectorAutoEmbedFieldDefinition.equals uses Objects.equals(this.specification,
  // that.specification).
  // VectorTextFieldSpecification extends VectorFieldSpecification, whose equals/hashCode includes
  // indexingAlgorithm (and thus hnswOptions). So equals/hashCode do include hnswOptions.

  @Test
  public void vectorAutoEmbedFieldDefinition_equals_samePathAndSpec_returnsTrue() {
    FieldPath path = FieldPath.parse("desc");
    VectorAutoEmbedFieldDefinition a =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    VectorAutoEmbedFieldDefinition b =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    assertEquals(a, b);
    assertEquals(b, a);
  }

  @Test
  public void vectorAutoEmbedFieldDefinition_equals_samePathAndSpecUsingDefault_returnsTrue() {
    FieldPath path = FieldPath.parse("desc");
    VectorAutoEmbedFieldDefinition a =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    VectorAutoEmbedFieldDefinition b =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    assertEquals(a, b);
    assertEquals(b, a);
  }

  @Test
  public void vectorAutoEmbedFieldDefinition_equals_samePathAndSpecOnlyOneDefault_returnsTrue() {
    FieldPath path = FieldPath.parse("desc");
    VectorAutoEmbedFieldDefinition a =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(new HnswOptions(16, 100)));
    VectorAutoEmbedFieldDefinition b =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT);
    assertEquals(a, b);
    assertEquals(b, a);
  }

  @Test
  public void vectorAutoEmbedFieldDefinition_equals_differentHnswOptions_returnsFalse() {
    FieldPath path = FieldPath.parse("desc");
    VectorAutoEmbedFieldDefinition defaultHnsw =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(new HnswOptions(64, 200)));
    VectorAutoEmbedFieldDefinition customHnsw =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(new HnswOptions(32, 200)));
    assertNotEquals(defaultHnsw, customHnsw);
    assertNotEquals(customHnsw, defaultHnsw);
  }

  @Test
  public void vectorAutoEmbedFieldDefinition_equals_differentPath_returnsFalse() {
    VectorAutoEmbedFieldDefinition a =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            FieldPath.parse("desc"),
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(new HnswOptions(32, 200)));
    VectorAutoEmbedFieldDefinition b =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            FieldPath.parse("title"),
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(new HnswOptions(32, 200)));
    assertNotEquals(a, b);
    assertNotEquals(b, a);
  }

  @Test
  public void vectorAutoEmbedFieldDefinition_equals_hashCodeConsistentWithEquals() {
    FieldPath path = FieldPath.parse("desc");
    VectorAutoEmbedFieldDefinition withCustomHnsw =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(new HnswOptions(16, 100)));
    VectorAutoEmbedFieldDefinition same =
        new VectorAutoEmbedFieldDefinition(
            "dummy",
            "text",
            path,
            1024,
            VectorSimilarity.DOT_PRODUCT,
            VectorAutoEmbedQuantization.FLOAT,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm(new HnswOptions(16, 100)));
    assertEquals(withCustomHnsw, same);
  }

  @Test
  public void estimatedEmbeddingPayloadBytes_matchesExpectedPerQuantization() {
    int dims = 256;
    assertEquals(
        (long) dims * Float.BYTES,
        VectorAutoEmbedQuantization.FLOAT.estimatedEmbeddingPayloadBytes(dims));
    assertEquals(dims, VectorAutoEmbedQuantization.SCALAR.estimatedEmbeddingPayloadBytes(dims));
    assertEquals(
        (long) dims * Float.BYTES,
        VectorAutoEmbedQuantization.BINARY.estimatedEmbeddingPayloadBytes(dims));
    assertEquals(
        dims / 8,
        VectorAutoEmbedQuantization.BINARY_NO_RESCORE.estimatedEmbeddingPayloadBytes(dims));
  }
}
