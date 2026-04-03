package com.xgen.mongot.index.definition;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.parser.BsonDocumentParser;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.Test;

public class VectorDataFieldDefinitionTest {

  private static final VectorFieldSpecification SIMPLE_SPEC =
      new VectorFieldSpecification(
          3,
          VectorSimilarity.DOT_PRODUCT,
          VectorQuantization.NONE,
          new VectorIndexingAlgorithm.HnswIndexingAlgorithm());

  @Test
  public void testSimpleVectorField() {
    FieldPath path = FieldPath.parse("vectorPath");
    VectorDataFieldDefinition def = new VectorDataFieldDefinition(path, SIMPLE_SPEC);

    assertEquals(path, def.getPath());
    assertEquals(SIMPLE_SPEC, def.specification());
  }

  @Test
  public void testNestedVectorField() {
    // With the new syntax, nested vector fields are just regular fields with a path
    // that is a child of the index-level nestedRoot
    FieldPath path = FieldPath.parse("sections.paragraph.vector");
    VectorDataFieldDefinition def = new VectorDataFieldDefinition(path, SIMPLE_SPEC);

    assertEquals(path, def.getPath());
    assertEquals(SIMPLE_SPEC, def.specification());
  }

  @Test
  public void testSimpleVectorField_ToBson() {
    FieldPath path = FieldPath.parse("vectorPath");
    VectorDataFieldDefinition def = new VectorDataFieldDefinition(path, SIMPLE_SPEC);

    BsonDocument bson = def.toBson();

    assertEquals("vector", bson.getString("type").getValue());
    assertEquals("vectorPath", bson.getString("path").getValue());
    assertEquals(3, bson.getInt32("numDimensions").getValue());
    assertEquals("dotProduct", bson.getString("similarity").getValue());
  }

  @Test
  public void testNestedVectorField_ToBson() {
    FieldPath path = FieldPath.parse("sections.paragraph.vector");
    VectorDataFieldDefinition def = new VectorDataFieldDefinition(path, SIMPLE_SPEC);

    BsonDocument bson = def.toBson();

    assertEquals("vector", bson.getString("type").getValue());
    assertThat(bson.get("path").isString()).isTrue();
    assertEquals("sections.paragraph.vector", bson.getString("path").getValue());
    assertEquals(3, bson.getInt32("numDimensions").getValue());
    assertEquals("dotProduct", bson.getString("similarity").getValue());
  }

  @Test
  public void testSimpleVectorField_FromBson() throws BsonParseException {
    BsonDocument bson =
        new BsonDocument()
            .append("type", new BsonString("vector"))
            .append("path", new BsonString("vectorPath"))
            .append("numDimensions", new BsonInt32(3))
            .append("similarity", new BsonString("dotProduct"));

    VectorDataFieldDefinition def =
        VectorDataFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson).build());

    assertEquals(FieldPath.parse("vectorPath"), def.getPath());
    assertEquals(3, def.specification().numDimensions());
    assertEquals(VectorSimilarity.DOT_PRODUCT, def.specification().similarity());
  }

  @Test
  public void testNestedVectorField_FromBson() throws BsonParseException {
    BsonDocument bson =
        new BsonDocument()
            .append("type", new BsonString("vector"))
            .append("path", new BsonString("sections.paragraph.vector"))
            .append("numDimensions", new BsonInt32(3))
            .append("similarity", new BsonString("dotProduct"));

    VectorDataFieldDefinition def =
        VectorDataFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson).build());

    assertEquals(FieldPath.parse("sections.paragraph.vector"), def.getPath());
    assertEquals(3, def.specification().numDimensions());
    assertEquals(VectorSimilarity.DOT_PRODUCT, def.specification().similarity());
  }

  @Test
  public void testSimpleVectorField_RoundTrip() throws BsonParseException {
    FieldPath path = FieldPath.parse("vectorPath");
    VectorDataFieldDefinition original = new VectorDataFieldDefinition(path, SIMPLE_SPEC);

    BsonDocument bson = original.toBson();
    VectorDataFieldDefinition parsed =
        VectorDataFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson).build());

    assertEquals(original, parsed);
  }

  @Test
  public void testNestedVectorField_RoundTrip() throws BsonParseException {
    FieldPath path = FieldPath.parse("sections.paragraph.vector");
    VectorDataFieldDefinition original = new VectorDataFieldDefinition(path, SIMPLE_SPEC);

    BsonDocument bson = original.toBson();
    VectorDataFieldDefinition parsed =
        VectorDataFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson).build());

    assertEquals(original, parsed);
  }

  @Test
  public void testEquality_SimpleFields() {
    FieldPath path = FieldPath.parse("vectorPath");
    VectorDataFieldDefinition def1 = new VectorDataFieldDefinition(path, SIMPLE_SPEC);
    VectorDataFieldDefinition def2 = new VectorDataFieldDefinition(path, SIMPLE_SPEC);

    assertEquals(def1, def2);
    assertEquals(def1.hashCode(), def2.hashCode());
  }

  @Test
  public void testEquality_SamePathAndSpec() {
    FieldPath path = FieldPath.parse("sections.paragraph.vector");
    VectorDataFieldDefinition def1 = new VectorDataFieldDefinition(path, SIMPLE_SPEC);
    VectorDataFieldDefinition def2 = new VectorDataFieldDefinition(path, SIMPLE_SPEC);

    assertEquals(def1, def2);
    assertEquals(def1.hashCode(), def2.hashCode());
  }

  @Test
  public void testInequality_DifferentPathsSameSpec() {
    VectorDataFieldDefinition def1 =
        new VectorDataFieldDefinition(FieldPath.parse("a.vector"), SIMPLE_SPEC);
    VectorDataFieldDefinition def2 =
        new VectorDataFieldDefinition(FieldPath.parse("b.vector"), SIMPLE_SPEC);

    assertNotEquals(def1, def2);
    assertNotEquals(def1.hashCode(), def2.hashCode());
  }

  @Test
  public void testComplexNestedPath() throws BsonParseException {
    FieldPath path = FieldPath.parse("data.sections.items.embedding");
    VectorDataFieldDefinition def = new VectorDataFieldDefinition(path, SIMPLE_SPEC);

    BsonDocument bson = def.toBson();
    VectorDataFieldDefinition parsed =
        VectorDataFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson).build());

    assertEquals(def.getPath(), parsed.getPath());
    assertEquals(def.specification(), parsed.specification());
  }

  @Test
  public void testTwoDifferentPaths() throws BsonParseException {
    FieldPath path1 = FieldPath.parse("reviews.sentiment.embedding");
    FieldPath path2 = FieldPath.parse("comments.text.vector");

    VectorFieldSpecification spec1 =
        new VectorFieldSpecification(
            256,
            VectorSimilarity.COSINE,
            VectorQuantization.SCALAR,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm());
    VectorFieldSpecification spec2 =
        new VectorFieldSpecification(
            512,
            VectorSimilarity.EUCLIDEAN,
            VectorQuantization.NONE,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm());

    VectorDataFieldDefinition def1 = new VectorDataFieldDefinition(path1, spec1);
    VectorDataFieldDefinition def2 = new VectorDataFieldDefinition(path2, spec2);

    assertNotEquals(def1, def2);
    assertNotEquals(def1.hashCode(), def2.hashCode());

    assertEquals(FieldPath.parse("reviews.sentiment.embedding"), def1.getPath());
    assertEquals(256, def1.specification().numDimensions());
    assertEquals(VectorSimilarity.COSINE, def1.specification().similarity());

    assertEquals(FieldPath.parse("comments.text.vector"), def2.getPath());
    assertEquals(512, def2.specification().numDimensions());
    assertEquals(VectorSimilarity.EUCLIDEAN, def2.specification().similarity());

    BsonDocument bson1 = def1.toBson();
    BsonDocument bson2 = def2.toBson();

    VectorDataFieldDefinition parsed1 =
        VectorDataFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson1).build());
    VectorDataFieldDefinition parsed2 =
        VectorDataFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson2).build());

    assertEquals(def1.getPath(), parsed1.getPath());
    assertEquals(def1.specification(), parsed1.specification());
    assertEquals(def2.getPath(), parsed2.getPath());
    assertEquals(def2.specification(), parsed2.specification());
    assertNotEquals(parsed1, parsed2);

    assertThat(bson1.get("path").isString()).isTrue();
    assertEquals("reviews.sentiment.embedding", bson1.getString("path").getValue());
    assertEquals(256, bson1.getInt32("numDimensions").getValue());
    assertEquals("cosine", bson1.getString("similarity").getValue());

    assertThat(bson2.get("path").isString()).isTrue();
    assertEquals("comments.text.vector", bson2.getString("path").getValue());
    assertEquals(512, bson2.getInt32("numDimensions").getValue());
    assertEquals("euclidean", bson2.getString("similarity").getValue());
  }

  @Test
  public void testTwoPathsWithSamePrefix() throws BsonParseException {
    // Two vector fields with paths under the same prefix (e.g. same index nestedRoot)
    FieldPath path1 = FieldPath.parse("reviews.sentiment.embedding");
    FieldPath path2 = FieldPath.parse("reviews.summary.embedding");

    VectorFieldSpecification spec1 =
        new VectorFieldSpecification(
            256,
            VectorSimilarity.COSINE,
            VectorQuantization.SCALAR,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm());
    VectorFieldSpecification spec2 =
        new VectorFieldSpecification(
            512,
            VectorSimilarity.EUCLIDEAN,
            VectorQuantization.NONE,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm());

    VectorDataFieldDefinition def1 = new VectorDataFieldDefinition(path1, spec1);
    VectorDataFieldDefinition def2 = new VectorDataFieldDefinition(path2, spec2);

    assertNotEquals(def1, def2);
    assertNotEquals(def1.hashCode(), def2.hashCode());

    assertEquals(FieldPath.parse("reviews.sentiment.embedding"), def1.getPath());
    assertEquals(FieldPath.parse("reviews.summary.embedding"), def2.getPath());

    BsonDocument bson1 = def1.toBson();
    BsonDocument bson2 = def2.toBson();

    VectorDataFieldDefinition parsed1 =
        VectorDataFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson1).build());
    VectorDataFieldDefinition parsed2 =
        VectorDataFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson2).build());

    assertEquals(def1.getPath(), parsed1.getPath());
    assertEquals(def1.specification(), parsed1.specification());
    assertEquals(def2.getPath(), parsed2.getPath());
    assertEquals(def2.specification(), parsed2.specification());
    assertNotEquals(parsed1, parsed2);
  }
}
