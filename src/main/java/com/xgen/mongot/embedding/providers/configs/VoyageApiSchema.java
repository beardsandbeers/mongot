package com.xgen.mongot.embedding.providers.configs;

import com.xgen.mongot.util.bson.FloatVector;
import com.xgen.mongot.util.bson.Vector;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseContext;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentEncodable;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import com.xgen.mongot.util.bson.parser.TypeDescription;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonValue;

public class VoyageApiSchema {

  public static final String DEFAULT_ENCODING_FORMAT = "base64";

  /**
   * Set only while parsing {@link EmbedResponse#fromBson(DocumentParser, String)} so static {@link
   * EmbedVector.Fields} can decode {@code embedding} for the matching {@code output_dtype}. Always
   * cleared in {@code finally}.
   */
  private static final ThreadLocal<String> VOYAGE_PARSE_OUTPUT_DTYPE = new ThreadLocal<>();

  public static class EmbedRequest implements DocumentEncodable {
    public static class Fields {
      static final Field.Required<String> INPUT_TYPE =
          Field.builder("input_type").stringField().required();
      static final Field.Required<String> MODEL_ID =
          Field.builder("model").stringField().required();
      static final Field.Required<List<String>> INPUT =
          Field.builder("input").stringField().asList().required();
      static final Field.Required<Boolean> TRUNCATION =
          Field.builder("truncation").booleanField().required();
      static final Field.WithDefault<String> ENCODING_FORMAT =
          Field.builder("encoding_format")
              .stringField()
              .optional()
              .withDefault(DEFAULT_ENCODING_FORMAT);
      static final Field.Optional<String> SERVICE_TIER =
          Field.builder("service_tier").stringField().optional().noDefault();
      static final Field.Optional<BsonDocument> METADATA =
          Field.builder("metadata").documentField().optional().noDefault();
      static final Field.Optional<Integer> OUTPUT_DIMENSION =
          Field.builder("output_dimension").intField().optional().noDefault();
      static final Field.Optional<String> OUTPUT_DATATYPE =
          Field.builder("output_dtype").stringField().optional().noDefault();
    }

    public final String modelId;
    public final String inputType;
    public final List<String> input;
    public final String encodingFormat;
    public final boolean truncation;
    public final Optional<String> serviceTier;
    public final Optional<BsonDocument> metadata;
    public final Optional<Integer> outputDimension;
    public final Optional<String> outputDataType;

    public EmbedRequest(
        String modelId,
        String inputType,
        List<String> input,
        String encodingFormat,
        boolean truncation,
        Optional<BsonDocument> metadata,
        Optional<String> serviceTier,
        Optional<Integer> outputDimension,
        Optional<String> outputDataType) {
      this.modelId = modelId;
      this.inputType = inputType;
      this.input = input;
      this.encodingFormat = encodingFormat;
      this.truncation = truncation;
      this.metadata = metadata;
      this.serviceTier = serviceTier;
      this.outputDimension = outputDimension;
      this.outputDataType = outputDataType;
    }

    public static EmbedRequest fromBson(DocumentParser parser) throws BsonParseException {
      return new EmbedRequest(
          parser.getField(Fields.MODEL_ID).unwrap(),
          parser.getField(Fields.INPUT_TYPE).unwrap(),
          parser.getField(Fields.INPUT).unwrap(),
          parser.getField(Fields.ENCODING_FORMAT).unwrap(),
          parser.getField(Fields.TRUNCATION).unwrap(),
          parser.getField(Fields.METADATA).unwrap(),
          parser.getField(Fields.SERVICE_TIER).unwrap(),
          parser.getField(Fields.OUTPUT_DIMENSION).unwrap(),
          parser.getField(Fields.OUTPUT_DATATYPE).unwrap());
    }

    @Override
    public BsonDocument toBson() {
      return BsonDocumentBuilder.builder()
          .field(Fields.INPUT_TYPE, this.inputType)
          .field(Fields.MODEL_ID, this.modelId)
          .field(Fields.INPUT, this.input)
          .field(Fields.ENCODING_FORMAT, this.encodingFormat)
          .field(Fields.TRUNCATION, this.truncation)
          .field(Fields.SERVICE_TIER, this.serviceTier)
          .field(Fields.METADATA, this.metadata)
          .field(Fields.OUTPUT_DIMENSION, this.outputDimension)
          .field(Fields.OUTPUT_DATATYPE, this.outputDataType)
          .build();
    }
  }

  public static class EmbedResponse implements DocumentEncodable {

    public static class Fields {
      static final Field.Required<String> OBJECT_TYPE =
          Field.builder("object").stringField().required();
      static final Field.Required<List<EmbedVector>> DATA =
          Field.builder("data")
              .classField(EmbedVector::fromBson)
              .allowUnknownFields()
              .asList()
              .required();
      static final Field.Required<EmbedUsage> EMBED_USAGE =
          Field.builder("usage").classField(EmbedUsage::fromBson).allowUnknownFields().required();
    }

    public static EmbedResponse fromBson(DocumentParser parser, String voyageOutputDataType)
        throws BsonParseException {
      VOYAGE_PARSE_OUTPUT_DTYPE.set(voyageOutputDataType);
      try {
        return new EmbedResponse(
            parser.getField(Fields.OBJECT_TYPE).unwrap(),
            parser.getField(Fields.DATA).unwrap(),
            parser.getField(Fields.EMBED_USAGE).unwrap());
      } finally {
        VOYAGE_PARSE_OUTPUT_DTYPE.remove();
      }
    }

    public final String objectType;
    public final List<EmbedVector> data;
    public final EmbedUsage embedUsage;

    public EmbedResponse(String objectType, List<EmbedVector> data, EmbedUsage embedUsage) {
      this.objectType = objectType;
      this.data = data;
      this.embedUsage = embedUsage;
    }

    @Override
    public BsonDocument toBson() {
      return BsonDocumentBuilder.builder()
          .field(Fields.OBJECT_TYPE, this.objectType)
          .field(Fields.DATA, this.data)
          .field(Fields.EMBED_USAGE, this.embedUsage)
          .build();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      EmbedResponse that = (EmbedResponse) o;
      return this.objectType.equals(that.objectType)
          && this.data.equals(that.data)
          && this.embedUsage.equals(that.embedUsage);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.objectType, this.data, this.embedUsage);
    }
  }

  public static class EmbedVector implements DocumentEncodable {
    public static class Fields {
      static final Field.Required<String> OBJECT_TYPE =
          Field.builder("object").stringField().required();
      static final Field.Required<Vector> EMBEDDING =
          Field.builder("embedding").classField(EmbedVector::decodeVectorFromBsonValue).required();
      static final Field.Required<Integer> INDEX = Field.builder("index").intField().required();
    }

    public EmbedVector(String objectType, Vector embeddings, int index) {
      this.objectType = objectType;
      this.embedding = embeddings;
      this.index = index;
    }

    @Override
    public BsonDocument toBson() {
      return BsonDocumentBuilder.builder()
          .field(Fields.OBJECT_TYPE, this.objectType)
          .field(Fields.EMBEDDING, this.embedding)
          .field(Fields.INDEX, this.index)
          .build();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      EmbedVector that = (EmbedVector) o;
      return this.objectType.equals(that.objectType)
          && this.index == that.index
          && this.embedding.equals(that.embedding);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.objectType, this.embedding, this.index);
    }

    public final String objectType;
    public final Vector embedding;
    public final int index;

    public static EmbedVector fromBson(DocumentParser parser) throws BsonParseException {
      return new EmbedVector(
          parser.getField(Fields.OBJECT_TYPE).unwrap(),
          parser.getField(Fields.EMBEDDING).unwrap(),
          parser.getField(Fields.INDEX).unwrap());
    }

    /**
     * Decodes Voyage's base64 {@code embedding} string. When {@link EmbedResponse#fromBson} runs,
     * it sets the request's {@code output_dtype} on a thread-local so this matches the API;
     * otherwise decoding assumes {@code float}.
     *
     * <p>Scalar {@code uint8} uses the same one-byte-per-dimension layout as {@code int8}; values
     * 0-255 are represented with the usual Java signed {@code byte} encoding.
     */
    public static Vector decodeVectorFromBsonValue(BsonParseContext context, BsonValue value)
        throws BsonParseException {
      if (!value.isString()) {
        return context.handleUnexpectedType(TypeDescription.STRING, value.getBsonType());
      }
      String base64Vector = value.asString().getValue();
      try {
        byte[] decoded = Base64.getDecoder().decode(base64Vector);
        ByteBuffer byteBuffer = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
        String dtype =
            Optional.ofNullable(VOYAGE_PARSE_OUTPUT_DTYPE.get())
                .orElse("float")
                .toLowerCase(Locale.ROOT);
        return switch (dtype) {
          case "float" -> {
            if (byteBuffer.remaining() % Float.BYTES != 0) {
              yield context.handleSemanticError(
                  "float embedding byte length is not a multiple of 4");
            }
            yield parseFloatVector(byteBuffer);
          }
          case "int8", "uint8" -> parseInt8Vector(byteBuffer);
          case "binary" -> parseVoyageBitPackedVector(byteBuffer, true);
          case "ubinary" -> parseVoyageBitPackedVector(byteBuffer, false);
          default ->
              context.handleSemanticError(
                  "Unsupported Voyage output_dtype for embedding decode: " + dtype);
        };
      } catch (IllegalArgumentException e) {
        return context.handleSemanticError("Invalid base64 for embedding");
      }
    }
  }

  public static class EmbedUsage implements DocumentEncodable {
    public static class Fields {
      static final Field.Required<Integer> TOTAL_TOKENS =
          Field.builder("total_tokens").intField().required();
    }

    public static EmbedUsage fromBson(DocumentParser parser) throws BsonParseException {
      return new EmbedUsage(parser.getField(Fields.TOTAL_TOKENS).unwrap());
    }

    public EmbedUsage(int totalTokens) {
      this.totalTokens = totalTokens;
    }

    @Override
    public BsonDocument toBson() {
      return BsonDocumentBuilder.builder().field(Fields.TOTAL_TOKENS, this.totalTokens).build();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      EmbedUsage that = (EmbedUsage) o;
      return this.totalTokens == that.totalTokens;
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.totalTokens);
    }

    public final int totalTokens;
  }

  private static Vector parseFloatVector(ByteBuffer bsonInput) {
    FloatBuffer floatBuffer = bsonInput.asFloatBuffer();
    float[] vector = new float[floatBuffer.remaining()];
    floatBuffer.get(vector);
    return Vector.fromFloats(vector, FloatVector.OriginalType.NATIVE);
  }

  private static Vector parseInt8Vector(ByteBuffer byteBuffer) {
    byte[] bytes = new byte[byteBuffer.remaining()];
    byteBuffer.get(bytes);
    return Vector.fromBytes(bytes);
  }

  /**
   * Voyage bit-packed embeddings: each byte holds 8 quantized bits.
   *
   * <p>{@code binary} uses <a
   * href="https://docs.voyageai.com/docs/flexible-dimensions-and-quantization">offset binary</a>:
   * API values are signed int8 where {@code stored = (unsignedPackedByte - 128)}. We recover the
   * raw packed byte with {@code unsignedPackedByte = stored + 128} before {@link Vector#fromBits}.
   *
   * <p>{@code ubinary} uses raw unsigned packed bytes (0–255) with no offset.
   */
  private static Vector parseVoyageBitPackedVector(ByteBuffer byteBuffer, boolean offsetBinary) {
    byte[] bytes = new byte[byteBuffer.remaining()];
    byteBuffer.get(bytes);
    if (offsetBinary) {
      for (int i = 0; i < bytes.length; i++) {
        bytes[i] = (byte) (bytes[i] + 128);
      }
    }
    return Vector.fromBits(bytes);
  }
}
