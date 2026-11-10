package com.xgen.mongot.standalone.ingestion;

import com.xgen.mongot.index.EncodedUserData;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/** A source-specific checkpoint; never interpreted as a MongoDB resume token or optime. */
public record StandaloneIndexCommitUserData(
    String instanceId,
    String collectionId,
    String generationId,
    String definitionHash,
    boolean buildComplete,
    long lastAppliedSequence) {
  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "sourceMode",
          "instanceId",
          "collectionUUID",
          "generationId",
          "definitionHash",
          "buildComplete",
          "lastAppliedSequence");

  public StandaloneIndexCommitUserData {
    if (!UUID.fromString(instanceId).toString().equals(instanceId)
        || !UUID.fromString(collectionId).toString().equals(collectionId)
        || generationId.isBlank()
        || !definitionHash.matches("[0-9a-f]{64}")
        || lastAppliedSequence < 0) {
      throw new IllegalArgumentException("Invalid standalone checkpoint identity or position");
    }
  }

  public EncodedUserData encode() {
    return EncodedUserData.fromString(
        new BsonDocument("schemaVersion", new BsonInt32(1))
            .append("sourceMode", new BsonString("api"))
            .append("instanceId", new BsonString(this.instanceId))
            .append("collectionUUID", new BsonString(this.collectionId))
            .append("generationId", new BsonString(this.generationId))
            .append("definitionHash", new BsonString(this.definitionHash))
            .append("buildComplete", BsonBoolean.valueOf(this.buildComplete))
            .append("lastAppliedSequence", new BsonInt64(this.lastAppliedSequence))
            .toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build()));
  }

  public static StandaloneIndexCommitUserData decode(EncodedUserData encoded) {
    try {
      var document = BsonDocument.parse(encoded.asString());
      if (!document.keySet().equals(FIELDS)
          || document.getInt32("schemaVersion").getValue() != 1
          || !document.getString("sourceMode").getValue().equals("api")) {
        throw new IllegalArgumentException("Unsupported standalone checkpoint schema or source");
      }
      return new StandaloneIndexCommitUserData(
          document.getString("instanceId").getValue(),
          document.getString("collectionUUID").getValue(),
          document.getString("generationId").getValue(),
          document.getString("definitionHash").getValue(),
          document.getBoolean("buildComplete").getValue(),
          document.getInt64("lastAppliedSequence").getValue());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Unreadable standalone checkpoint", e);
    }
  }

  public void validateIdentity(
      String expectedInstance,
      String expectedCollection,
      String expectedGeneration,
      String expectedDefinitionHash,
      long acceptedSequence) {
    if (!this.instanceId.equals(expectedInstance)
        || !this.collectionId.equals(expectedCollection)
        || !this.generationId.equals(expectedGeneration)
        || !this.definitionHash.equals(expectedDefinitionHash)
        || this.lastAppliedSequence > acceptedSequence) {
      throw new IllegalArgumentException(
          "Standalone checkpoint does not match its source generation");
    }
  }
}
