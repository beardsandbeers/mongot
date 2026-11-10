package com.xgen.mongot.standalone.ingestion;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.xgen.mongot.index.EncodedUserData;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.junit.Test;

public class StandaloneIndexCommitUserDataTest {
  private static final String INSTANCE = "6d7d6982-8e75-4624-8990-c3fdd5c1eb9d";
  private static final String COLLECTION = "a5f61f66-6d7d-456c-a365-8bfc3d63cf1b";
  private static final String HASH = "a".repeat(64);

  @Test
  public void decode_roundTrip_preservesSourceIdentityAndSequence() {
    var checkpoint =
        new StandaloneIndexCommitUserData(INSTANCE, COLLECTION, "generation", HASH, true, 42);

    assertThat(StandaloneIndexCommitUserData.decode(checkpoint.encode())).isEqualTo(checkpoint);
  }

  @Test
  public void decode_mongoDbCheckpoint_rejects() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            StandaloneIndexCommitUserData.decode(EncodedUserData.fromString("{resumeToken: {}}")));
  }

  @Test
  public void decode_unknownSchema_rejects() {
    var checkpoint =
        new StandaloneIndexCommitUserData(INSTANCE, COLLECTION, "generation", HASH, true, 42);
    var document = BsonDocument.parse(checkpoint.encode().asString());
    document.put("schemaVersion", new BsonInt32(2));
    var encoded =
        EncodedUserData.fromString(
            document.toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build()));

    assertThrows(
        IllegalArgumentException.class, () -> StandaloneIndexCommitUserData.decode(encoded));
  }

  @Test
  public void validateIdentity_differentGeneration_rejects() {
    var checkpoint =
        new StandaloneIndexCommitUserData(INSTANCE, COLLECTION, "generation", HASH, true, 42);

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.validateIdentity(INSTANCE, COLLECTION, "other", HASH, 100));
  }

  @Test
  public void validateIdentity_aheadOfSource_rejects() {
    var checkpoint =
        new StandaloneIndexCommitUserData(INSTANCE, COLLECTION, "generation", HASH, true, 42);

    assertThrows(
        IllegalArgumentException.class,
        () -> checkpoint.validateIdentity(INSTANCE, COLLECTION, "generation", HASH, 41));
  }
}
