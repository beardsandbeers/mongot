package com.xgen.mongot.index.definition;

import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.parser.BsonDocumentParser;
import java.util.Map;
import org.bson.BsonDocument;
import org.junit.Assert;
import org.junit.Test;

public class VectorEmbeddedDocumentsFieldDefinitionTest {

  @Test
  public void testGetType() {
    FieldPath path = FieldPath.parse("sections");
    VectorEmbeddedDocumentsFieldDefinition definition =
        new VectorEmbeddedDocumentsFieldDefinition(path, Map.of());
    Assert.assertEquals(VectorIndexFieldDefinition.Type.EMBEDDED_DOCUMENTS, definition.getType());
  }

  @Test
  public void testEmptyFields() {
    FieldPath path = FieldPath.parse("sections");
    VectorEmbeddedDocumentsFieldDefinition definition =
        new VectorEmbeddedDocumentsFieldDefinition(path, Map.of());

    Assert.assertEquals(path, definition.getPath());
    Assert.assertTrue(definition.fields().isEmpty());
  }

  @Test
  public void testWithFilterFields() {
    FieldPath path = FieldPath.parse("sections");
    FieldPath categoryPath = FieldPath.parse("sections.category");
    FieldPath priorityPath = FieldPath.parse("sections.priority");

    VectorIndexFilterFieldDefinition categoryFilter =
        VectorIndexFilterFieldDefinition.create(categoryPath);
    VectorIndexFilterFieldDefinition priorityFilter =
        VectorIndexFilterFieldDefinition.create(priorityPath);

    Map<String, VectorIndexFieldDefinition> fields =
        Map.of("category", categoryFilter, "priority", priorityFilter);

    VectorEmbeddedDocumentsFieldDefinition definition =
        new VectorEmbeddedDocumentsFieldDefinition(path, fields);

    Assert.assertEquals(path, definition.getPath());
    Assert.assertEquals(2, definition.fields().size());
    Assert.assertEquals(categoryFilter, definition.fields().get("category"));
    Assert.assertEquals(priorityFilter, definition.fields().get("priority"));
  }

  @Test
  public void testToBson() {
    FieldPath path = FieldPath.parse("sections");
    FieldPath categoryPath = FieldPath.parse("sections.category");

    VectorIndexFilterFieldDefinition categoryFilter =
        VectorIndexFilterFieldDefinition.create(categoryPath);
    Map<String, VectorIndexFieldDefinition> fields = Map.of("category", categoryFilter);

    VectorEmbeddedDocumentsFieldDefinition definition =
        new VectorEmbeddedDocumentsFieldDefinition(path, fields);

    BsonDocument bson = definition.toBson();

    Assert.assertEquals("embeddedDocuments", bson.getString("type").getValue());
    Assert.assertEquals("sections", bson.getString("path").getValue());
    Assert.assertTrue(bson.isDocument("fields"));
    Assert.assertTrue(bson.getDocument("fields").containsKey("category"));
  }

  @Test
  public void testFromBson() throws Exception {
    String bsonString =
        """
        {
          "type": "embeddedDocuments",
          "path": "sections",
          "fields": {
            "category": {
              "type": "filter",
              "path": "sections.category"
            }
          }
        }
        """;

    BsonDocument bson = BsonDocument.parse(bsonString);
    VectorEmbeddedDocumentsFieldDefinition definition =
        VectorEmbeddedDocumentsFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson).build());

    Assert.assertEquals(FieldPath.parse("sections"), definition.getPath());
    Assert.assertEquals(1, definition.fields().size());
    Assert.assertTrue(definition.fields().containsKey("category"));

    VectorIndexFieldDefinition categoryField = definition.fields().get("category");
    Assert.assertEquals(VectorIndexFieldDefinition.Type.FILTER, categoryField.getType());
    Assert.assertEquals(FieldPath.parse("sections.category"), categoryField.getPath());
  }

  @Test
  public void testRoundTrip() throws Exception {
    FieldPath path = FieldPath.parse("sections");
    FieldPath categoryPath = FieldPath.parse("sections.category");
    FieldPath priorityPath = FieldPath.parse("sections.priority");

    VectorIndexFilterFieldDefinition categoryFilter =
        VectorIndexFilterFieldDefinition.create(categoryPath);
    VectorIndexFilterFieldDefinition priorityFilter =
        VectorIndexFilterFieldDefinition.create(priorityPath);

    Map<String, VectorIndexFieldDefinition> fields =
        Map.of("category", categoryFilter, "priority", priorityFilter);

    VectorEmbeddedDocumentsFieldDefinition original =
        new VectorEmbeddedDocumentsFieldDefinition(path, fields);

    BsonDocument bson = original.toBson();
    VectorEmbeddedDocumentsFieldDefinition parsed =
        VectorEmbeddedDocumentsFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson).build());

    Assert.assertEquals(original.getPath(), parsed.getPath());
    Assert.assertEquals(original.fields().size(), parsed.fields().size());
    Assert.assertEquals(
        original.fields().get("category").getPath(), parsed.fields().get("category").getPath());
    Assert.assertEquals(
        original.fields().get("priority").getPath(), parsed.fields().get("priority").getPath());
  }

  @Test
  public void testEquals() {
    FieldPath path1 = FieldPath.parse("sections");
    FieldPath path2 = FieldPath.parse("sections");
    FieldPath path3 = FieldPath.parse("items");

    VectorIndexFilterFieldDefinition filter1 =
        VectorIndexFilterFieldDefinition.create(FieldPath.parse("sections.category"));
    VectorIndexFilterFieldDefinition filter2 =
        VectorIndexFilterFieldDefinition.create(FieldPath.parse("sections.priority"));

    VectorEmbeddedDocumentsFieldDefinition def1 =
        new VectorEmbeddedDocumentsFieldDefinition(path1, Map.of("category", filter1));
    VectorEmbeddedDocumentsFieldDefinition def2 =
        new VectorEmbeddedDocumentsFieldDefinition(path2, Map.of("category", filter1));
    VectorEmbeddedDocumentsFieldDefinition def3 =
        new VectorEmbeddedDocumentsFieldDefinition(path3, Map.of("category", filter1));
    VectorEmbeddedDocumentsFieldDefinition def4 =
        new VectorEmbeddedDocumentsFieldDefinition(path1, Map.of("priority", filter2));

    // Same path and fields
    Assert.assertEquals(def1, def2);
    Assert.assertEquals(def1.hashCode(), def2.hashCode());

    // Different path
    Assert.assertNotEquals(def1, def3);

    // Different fields
    Assert.assertNotEquals(def1, def4);
  }

  @Test
  public void testEmptyFieldsRoundTrip() throws Exception {
    FieldPath path = FieldPath.parse("sections");
    VectorEmbeddedDocumentsFieldDefinition original =
        new VectorEmbeddedDocumentsFieldDefinition(path, Map.of());

    BsonDocument bson = original.toBson();
    VectorEmbeddedDocumentsFieldDefinition parsed =
        VectorEmbeddedDocumentsFieldDefinition.fromBson(BsonDocumentParser.fromRoot(bson).build());

    Assert.assertEquals(original.getPath(), parsed.getPath());
    Assert.assertTrue(parsed.fields().isEmpty());
  }
}
