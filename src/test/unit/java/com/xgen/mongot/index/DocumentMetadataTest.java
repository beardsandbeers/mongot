package com.xgen.mongot.index;

import com.xgen.mongot.util.BsonUtils;
import java.util.Optional;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.types.ObjectId;
import org.junit.Assert;
import org.junit.Test;

public class DocumentMetadataTest {

  @Test
  public void testDefaultsToFalseWhenDeletedFlagIsAbsent() {
    var indexId = new ObjectId();
    var metadataNamespace =
        new BsonDocument(indexId.toString(), new BsonDocument("_id", new BsonInt32(1)));
    var metadata =
        DocumentMetadata.fromMetadataNamespace(
            Optional.of(BsonUtils.documentToRaw(metadataNamespace)), indexId);
    Assert.assertFalse(metadata.isDeleted());
  }

  @Test
  public void testDefaultsToFalseWhenDeletedFlagIsPresent() {
    var indexId = new ObjectId();
    var metadataNamespace =
        new BsonDocument(
            indexId.toString(),
            new BsonDocument("_id", new BsonInt32(1)).append("deleted", BsonBoolean.TRUE));
    var metadata =
        DocumentMetadata.fromMetadataNamespace(
            Optional.of(BsonUtils.documentToRaw(metadataNamespace)), indexId);
    Assert.assertTrue(metadata.isDeleted());
  }

  @Test
  public void testIdIfPresent() {
    var indexId = new ObjectId();
    var metadataNamespace =
        new BsonDocument(indexId.toString(), new BsonDocument("_id", new BsonInt32(1)));
    var metadata =
        DocumentMetadata.fromMetadataNamespace(
            Optional.of(BsonUtils.documentToRaw(metadataNamespace)), indexId);
    Assert.assertEquals(metadata.getId(), Optional.of(new BsonInt32(1)));
  }

  @Test
  public void testIdIsAbsent() {
    var indexId = new ObjectId();
    var metadataNamespace =
        new BsonDocument(indexId.toString(), new BsonDocument("deleted", BsonBoolean.TRUE));
    var metadata =
        DocumentMetadata.fromMetadataNamespace(
            Optional.of(BsonUtils.documentToRaw(metadataNamespace)), indexId);
    Assert.assertEquals(metadata.getId(), Optional.empty());
  }

  @Test
  public void testMetadataNamespaceIsEmpty() {
    var metadata =
        DocumentMetadata.fromMetadataNamespace(
            Optional.of(BsonUtils.documentToRaw(new BsonDocument())), new ObjectId());
    Assert.assertEquals(metadata.getId(), Optional.empty());
    Assert.assertFalse(metadata.isDeleted());
  }

  @Test
  public void testDocumentIsEmpty() {
    var metadata = DocumentMetadata.fromMetadataNamespace(Optional.empty(), new ObjectId());
    Assert.assertEquals(metadata.getId(), Optional.empty());
    Assert.assertFalse(metadata.isDeleted());
  }
}
