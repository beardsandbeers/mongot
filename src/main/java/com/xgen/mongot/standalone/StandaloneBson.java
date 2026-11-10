package com.xgen.mongot.standalone;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.index.lucene.util.LuceneDocumentIdEncoder;
import com.xgen.mongot.standalone.grpc.Mutation;
import com.xgen.mongot.standalone.grpc.MutationType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.apache.lucene.index.IndexWriter;
import org.bson.BsonType;
import org.bson.RawBsonDocument;

/** Structural validation before any document reaches the durable acceptance boundary. */
public final class StandaloneBson {
  public record ValidatedMutation(RawBsonDocument document, byte[] encodedId, boolean delete) {}

  private StandaloneBson() {}

  /**
   * Validates every nested value before using the driver's map representation, which would lose
   * duplicate fields. Exactly one BSON document is accepted; trailing bytes are an error.
   */
  public static RawBsonDocument document(byte[] bytes, int maxBytes, int maxDepth) {
    if (bytes.length < 5 || bytes.length > maxBytes) {
      throw new IllegalArgumentException("BSON document length is outside the configured limit");
    }
    if (ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() != bytes.length) {
      throw new IllegalArgumentException("BSON must contain exactly one complete document");
    }
    var scanner = new Scanner(bytes, maxDepth);
    scanner.document(bytes.length, 1, false);
    return new RawBsonDocument(bytes);
  }

  public static ValidatedMutation mutation(Mutation mutation, int maxBytes, int maxDepth) {
    if (mutation.getType() == MutationType.MUTATION_TYPE_UNSPECIFIED
        || mutation.getType() == MutationType.UNRECOGNIZED) {
      throw new IllegalArgumentException("A supported mutation type is required");
    }
    var document = document(mutation.getBson().toByteArray(), maxBytes, maxDepth);
    var id = document.get("_id");
    if (id == null
        || !Set.of(BsonType.STRING, BsonType.OBJECT_ID, BsonType.INT32, BsonType.INT64)
            .contains(id.getBsonType())) {
      throw new IllegalArgumentException("_id must be a string, ObjectId, int32 or int64");
    }
    boolean delete = mutation.getType() == MutationType.MUTATION_TYPE_DELETE;
    if (delete && document.size() != 1) {
      throw new IllegalArgumentException("DELETE requires exactly the _id field");
    }
    byte[] encodedId = LuceneDocumentIdEncoder.encodeDocumentId(id);
    if (encodedId.length > IndexWriter.MAX_TERM_LENGTH) {
      throw new IllegalArgumentException("Encoded _id exceeds Lucene's maximum term length");
    }
    return new ValidatedMutation(document, encodedId, delete);
  }

  // Driver decoders intentionally tolerate some malformed inputs (for example noncanonical array
  // keys and invalid UTF-8). Validate the binary structure before retaining its original bytes.
  private static final class Scanner {
    private final byte[] bytes;
    private final ByteBuffer buffer;
    private final int maxDepth;

    private Scanner(byte[] bytes, int maxDepth) {
      this.bytes = bytes;
      this.buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      this.maxDepth = maxDepth;
    }

    private void document(int parentEnd, int depth, boolean array) {
      if (depth > this.maxDepth) {
        throw new IllegalArgumentException("BSON nesting exceeds the configured limit");
      }
      int start = this.buffer.position();
      int length = integer(parentEnd);
      if (length < 5 || length > parentEnd - start) {
        throw new IllegalArgumentException("Invalid nested BSON document length");
      }
      int end = start + length;
      Set<String> fields = new HashSet<>();
      @Var int arrayIndex = 0;
      while (this.buffer.position() < end - 1) {
        int type = octet(end - 1);
        String name = cstring(end - 1);
        if ((array && !name.equals(Integer.toString(arrayIndex++)))
            || (!array && !fields.add(name))) {
          throw new IllegalArgumentException("Duplicate field name or noncanonical BSON array key");
        }
        value(type, end - 1, depth);
      }
      if (this.buffer.position() != end - 1 || octet(end) != 0) {
        throw new IllegalArgumentException("Invalid BSON document terminator");
      }
    }

    private void value(int type, int end, int depth) {
      switch (type) {
        case 1, 9, 17, 18 -> skip(8, end); // double, datetime, timestamp, int64
        case 2, 13, 14 -> string(end); // string, JavaScript, symbol
        case 3 -> document(end, depth + 1, false);
        case 4 -> document(end, depth + 1, true);
        case 5 -> {
          @Var int length = integer(end);
          int subtype = octet(end);
          if (subtype == 2) {
            if (length < 4 || integer(end) != length - 4) {
              throw new IllegalArgumentException("Invalid legacy BSON binary length");
            }
            length -= 4;
          }
          skip(length, end);
        }
        case 6, 10, 127, 255 -> {} // undefined, null, maxKey, minKey
        case 7 -> skip(12, end); // ObjectId
        case 8 -> {
          if (octet(end) > 1) {
            throw new IllegalArgumentException("Invalid BSON boolean");
          }
        }
        case 11 -> {
          cstring(end);
          cstring(end);
        }
        case 12 -> { // DBPointer
          string(end);
          skip(12, end);
        }
        case 15 -> {
          int start = this.buffer.position();
          int length = integer(end);
          if (length < 14 || length > end - start) {
            throw new IllegalArgumentException("Invalid JavaScript-with-scope length");
          }
          int scopeEnd = start + length;
          string(scopeEnd);
          document(scopeEnd, depth + 1, false);
          if (this.buffer.position() != scopeEnd) {
            throw new IllegalArgumentException("Trailing JavaScript scope payload");
          }
        }
        case 16 -> skip(4, end);
        case 19 -> skip(16, end); // decimal128
        default -> throw new IllegalArgumentException("Invalid BSON element type");
      }
    }

    private int integer(int end) {
      require(4, end);
      return this.buffer.getInt();
    }

    private int octet(int end) {
      require(1, end);
      return Byte.toUnsignedInt(this.buffer.get());
    }

    private void require(int count, int end) {
      if (count < 0 || count > end - this.buffer.position()) {
        throw new IllegalArgumentException("Truncated BSON value");
      }
    }

    private void skip(int count, int end) {
      require(count, end);
      this.buffer.position(this.buffer.position() + count);
    }

    private String cstring(int end) {
      int start = this.buffer.position();
      while (octet(end) != 0) {
        // The boundary check in octet requires a terminator inside this containing document.
      }
      return utf8(start, this.buffer.position() - start - 1);
    }

    private void string(int end) {
      int length = integer(end);
      if (length < 1) {
        throw new IllegalArgumentException("Invalid BSON string length");
      }
      require(length, end);
      utf8(this.buffer.position(), length - 1);
      skip(length - 1, end);
      if (octet(end) != 0) {
        throw new IllegalArgumentException("Invalid BSON string terminator");
      }
    }

    private String utf8(int offset, int length) {
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this.bytes, offset, length))
            .toString();
      } catch (CharacterCodingException e) {
        throw new IllegalArgumentException("Invalid UTF-8 in BSON", e);
      }
    }
  }
}
