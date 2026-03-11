package com.xgen.mongot.server.message;

import com.google.common.base.Objects;
import com.xgen.mongot.util.BsonUtils;
import io.netty.buffer.ByteBuf;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;

public final class MessageSectionBody implements MessageSection {

  private final int size;
  public final RawBsonDocument body;

  public MessageSectionBody(BsonDocument body) {
    this(new RawBsonDocument(body, BsonUtils.BSON_DOCUMENT_CODEC));
  }

  public MessageSectionBody(RawBsonDocument body) {
    this.body = body;
    this.size = body.getByteBuffer().remaining() + 1;
  }

  @Override
  public int size() {
    return this.size;
  }

  @Override
  public void append(ByteBuf out) {
    out.ensureWritable(this.size).writeByte(0x00).writeBytes(this.body.getByteBuffer().asNIO());
  }

  public static MessageSectionBody fromBytes(ByteBuf body) {
    RawBsonDocument doc = MessageUtils.rawBsonDocumentFromBytes(body);
    return new MessageSectionBody(doc);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof MessageSectionBody that)) {
      return false;
    }
    return this.size == that.size && Objects.equal(this.body, that.body);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(this.size, this.body);
  }
}
