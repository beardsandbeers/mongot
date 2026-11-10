package com.xgen.mongot.standalone;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.standalone.grpc.Mutation;
import com.xgen.mongot.standalone.grpc.MutationType;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.io.BasicOutputBuffer;
import org.junit.Test;

public class StandaloneBsonTest {
  @Test
  public void mutation_numericWidths_preservesDistinctEncodedIdentities() {
    var int32 = validate("{_id: {$numberInt: '1'}, title: 'a'}", MutationType.MUTATION_TYPE_INSERT);
    var int64 =
        validate("{_id: {$numberLong: '1'}, title: 'a'}", MutationType.MUTATION_TYPE_INSERT);

    assertThat(Arrays.equals(int32.encodedId(), int64.encodedId())).isFalse();
  }

  @Test
  public void document_nestedDuplicateField_rejectsBeforeMapParsing() {
    byte[] bytes = duplicateFields();

    assertThrows(IllegalArgumentException.class, () -> StandaloneBson.document(bytes, 1000, 100));
  }

  @Test
  public void document_trailingPayload_rejects() {
    var bytes = bson("{_id: 'a'}");
    var trailing = Arrays.copyOf(bytes, bytes.length + 1);

    assertThrows(
        IllegalArgumentException.class, () -> StandaloneBson.document(trailing, 1000, 100));
  }

  @Test
  public void document_incorrectNestedLength_rejects() {
    var bytes = bson("{child: {value: 'a'}}");
    ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(11, 5);

    assertThrows(IllegalArgumentException.class, () -> StandaloneBson.document(bytes, 1000, 100));
  }

  @Test
  public void document_nestedArrays_exceedsDepthLimit() {
    var bytes = bson("{a: [{b: [1]}]}");

    assertThrows(IllegalArgumentException.class, () -> StandaloneBson.document(bytes, 1000, 3));
  }

  @Test
  public void document_invalidUtf8_rejectsInsteadOfReplacingBytes() {
    var bytes = bson("{_id: 'a'}");
    bytes[bytes.length - 3] = (byte) 0xff;

    assertThrows(IllegalArgumentException.class, () -> StandaloneBson.document(bytes, 1000, 100));
  }

  @Test
  public void document_invalidBoolean_rejectsInsteadOfCoercingToTrue() {
    var bytes = bson("{flag: true}");
    bytes[bytes.length - 2] = 2;

    assertThrows(IllegalArgumentException.class, () -> StandaloneBson.document(bytes, 1000, 100));
  }

  @Test
  public void document_noncanonicalArrayKey_rejects() {
    var bytes = bson("{a: [1]}");
    bytes[12] = '1';

    assertThrows(IllegalArgumentException.class, () -> StandaloneBson.document(bytes, 1000, 100));
  }

  @Test
  public void document_bsonSpecificTypes_preservesOriginalBytes() {
    var bytes =
        bson(
            "{_id: {$oid: '507f1f77bcf86cd799439011'}, nested: [{value: true}],"
                + " date: {$date: {$numberLong: '1000'}}, decimal: {$numberDecimal: '1.2'},"
                + " binary: {$binary: {base64: 'AQID', subType: '00'}},"
                + " timestamp: {$timestamp: {t: 1, i: 2}},"
                + " code: {$code: 'return value;', $scope: {value: 1}}}");

    var document = StandaloneBson.document(bytes, 10000, 100);

    assertThat(document).isEqualTo(new RawBsonDocument(bytes));
    assertThat(document.getDecimal128("decimal").getValue().toString()).isEqualTo("1.2");
  }

  @Test
  public void mutation_deleteWithAdditionalFields_rejects() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validate("{_id: 'a', title: 'bad'}", MutationType.MUTATION_TYPE_DELETE));
  }

  @Test
  public void mutation_missingId_rejects() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validate("{title: 'a'}", MutationType.MUTATION_TYPE_UPSERT));
  }

  @Test
  public void mutation_nullId_rejects() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validate("{_id: null}", MutationType.MUTATION_TYPE_UPSERT));
  }

  @Test
  public void mutation_oversizedId_rejectsInsteadOfSilentLuceneOmission() {
    var mutation =
        Mutation.newBuilder()
            .setType(MutationType.MUTATION_TYPE_UPSERT)
            .setBson(ByteString.copyFrom(bson("{_id: '" + "x".repeat(32766) + "'}")))
            .build();

    assertThrows(
        IllegalArgumentException.class, () -> StandaloneBson.mutation(mutation, 100000, 100));
  }

  @Test
  public void mutation_deleteIdOnly_accepts() {
    var result = validate("{_id: 'a'}", MutationType.MUTATION_TYPE_DELETE);

    assertThat(result.delete()).isTrue();
    assertThat(result.document().getString("_id").getValue()).isEqualTo("a");
  }

  private static StandaloneBson.ValidatedMutation validate(String json, MutationType type) {
    return StandaloneBson.mutation(
        Mutation.newBuilder().setType(type).setBson(ByteString.copyFrom(bson(json))).build(),
        100000,
        100);
  }

  private static byte[] bson(String json) {
    var buffer =
        new RawBsonDocument(BsonDocument.parse(json), new BsonDocumentCodec()).getByteBuffer();
    var bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }

  private static byte[] duplicateFields() {
    try (var buffer = new BasicOutputBuffer();
        var writer = new BsonBinaryWriter(buffer)) {
      writer.writeStartDocument();
      writer.writeString("_id", "a");
      writer.writeStartDocument("nested");
      writer.writeString("field", "one");
      writer.writeString("field", "two");
      writer.writeEndDocument();
      writer.writeEndDocument();
      return buffer.toByteArray();
    }
  }
}
