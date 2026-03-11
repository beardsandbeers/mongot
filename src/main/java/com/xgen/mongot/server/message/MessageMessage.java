package com.xgen.mongot.server.message;

import com.google.errorprone.annotations.Var;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.util.ArrayList;
import java.util.List;
import org.bson.BsonDocument;

public record MessageMessage(
    MessageHeader messageHeader, int flagBits, List<MessageSection> sections)
    implements InboundMessage, OutboundMessage {

  public static MessageMessage forResponse(
      int requestId, int flagBits, List<MessageSection> sections) {
    return new MessageMessage(new MessageHeader(0, 17, requestId, OpCode.MSG), flagBits, sections);
  }

  @Override
  public MessageHeader getHeader() {
    return this.messageHeader;
  }

  @Override
  public OutboundMessage getOutboundMessage(BsonDocument body) {
    return MessageMessage.forResponse(
        this.getHeader().requestId(), 0, List.of(new MessageSectionBody(body)));
  }

  public static MessageMessage fromBytes(MessageHeader messageHeader, ByteBuf body) {

    int flagBits = body.readIntLE();

    List<MessageSection> sections = new ArrayList<>();
    while (body.readableBytes() >= 5) {
      sections.add(MessageSection.fromBytes(body));
    }

    return new MessageMessage(messageHeader, flagBits, sections);
  }

  @Override
  public ByteBuf toByteBuf(ByteBufAllocator alloc) {
    @Var int size = 20;
    for (MessageSection section : this.sections) {
      size += section.size();
    }

    ByteBuf out = alloc.buffer(size);

    out.writeIntLE(size);
    out.writeIntLE(this.messageHeader.requestId());
    out.writeIntLE(this.messageHeader.responseTo());
    out.writeIntLE(this.messageHeader.opCode().code);
    out.writeIntLE(this.flagBits);

    for (MessageSection section : this.sections) {
      section.append(out);
    }

    return out;
  }
}
