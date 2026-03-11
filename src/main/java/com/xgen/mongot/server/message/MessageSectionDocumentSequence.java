package com.xgen.mongot.server.message;

import com.google.common.base.Objects;
import com.google.errorprone.annotations.Var;
import com.xgen.mongot.util.BsonUtils;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;

public final class MessageSectionDocumentSequence implements MessageSection {

  private final int size;
  private final String id;
  private final List<RawBsonDocument> objects;

  public MessageSectionDocumentSequence(String id, List<BsonDocument> objects) {
    this.id = id;
    this.objects = new ArrayList<>(objects.size());

    // Size includes header of (byte type, int size, cstring)
    @Var int size = 1 + this.id.length() + 1;
    for (BsonDocument object : objects) {
      RawBsonDocument rawDoc = new RawBsonDocument(object, BsonUtils.BSON_DOCUMENT_CODEC);
      ByteBuffer buffer = rawDoc.getByteBuffer().asNIO();
      this.objects.add(rawDoc);
      size += buffer.remaining();
    }
    this.size = size;
  }

  @Override
  public int size() {
    return this.size;
  }

  @Override
  public void append(ByteBuf out) {
    int actualSize = this.size + 4;
    out.ensureWritable(actualSize);
    out.writeByte(0x01);
    out.writeIntLE(actualSize - 1);
    out.writeCharSequence(this.id, StandardCharsets.UTF_8);
    out.writeByte(0x00);

    for (RawBsonDocument buf : this.objects) {
      out.writeBytes(buf.getByteBuffer().asNIO());
    }
  }

  static MessageSectionDocumentSequence fromBytes(ByteBuf body) {
    int size = body.readIntLE();
    String id = MessageUtils.readCString(body);

    @Var int sizeLeft = size - 4 - id.length() - 1;

    List<BsonDocument> objects = new ArrayList<>();
    while (sizeLeft > 0) {
      RawBsonDocument doc = MessageUtils.rawBsonDocumentFromBytes(body);
      objects.add(doc);
      sizeLeft -= doc.getByteBuffer().remaining();
    }

    return new MessageSectionDocumentSequence(id, objects);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof MessageSectionDocumentSequence that)) {
      return false;
    }
    return this.size == that.size
        && Objects.equal(this.id, that.id)
        && Objects.equal(this.objects, that.objects);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.size, this.id, this.objects);
  }
}
