package com.xgen.mongot.server.message;

import io.netty.buffer.ByteBuf;

public sealed interface MessageSection permits MessageSectionBody, MessageSectionDocumentSequence {

  /** The size of the serialized form MessageSection in bytes. */
  int size();

  /** Serializes this MessageSection to an existing ByteBuf, extending the capacity if necessary. */
  void append(ByteBuf out);

  static MessageSection fromBytes(ByteBuf body) {

    int kind = body.readByte();
    return switch (kind) {
      case 0 -> MessageSectionBody.fromBytes(body);
      case 1 -> MessageSectionDocumentSequence.fromBytes(body);
      default -> throw new IllegalArgumentException("unknown message section kind " + kind);
    };
  }
}
