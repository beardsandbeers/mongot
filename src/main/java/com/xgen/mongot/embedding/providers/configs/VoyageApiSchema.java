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
import java.util.Objects;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonValue;

public class VoyageApiSchema {

  private static final String DEFAULT_ENCODING_FORMAT = "base64";

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
    }

    public static EmbedRequest fromBson(DocumentParser parser) throws BsonParseException {
      return new EmbedRequest(
          parser.getField(Fields.MODEL_ID).unwrap(),
          parser.getField(Fields.INPUT_TYPE).unwrap(),
          parser.getField(Fields.INPUT).unwrap(),
          parser.getField(Fields.ENCODING_FORMAT).unwrap(),
          parser.getField(Fields.TRUNCATION).unwrap(),
          parser.getField(Fields.METADATA).unwrap(),
          parser.getField(Fields.SERVICE_TIER).unwrap());
    }

    public final String modelId;
    public final String inputType;
    public final List<String> input;
    public final String encodingFormat;
    public final boolean truncation;
    public final Optional<String> serviceTier;
    public final Optional<BsonDocument> metadata;

    public EmbedRequest(
        String modelId,
        String inputType,
        List<String> input,
        boolean truncation,
        Optional<BsonDocument> metadata,
        Optional<String> serviceTier) {
      this(modelId, inputType, input, DEFAULT_ENCODING_FORMAT, truncation, metadata, serviceTier);
    }

    public EmbedRequest(
        String modelId,
        String inputType,
        List<String> input,
        String encodingFormat,
        boolean truncation,
        Optional<BsonDocument> metadata,
        Optional<String> serviceTier) {
      this.modelId = modelId;
      this.inputType = inputType;
      this.input = input;
      this.encodingFormat = encodingFormat;
      this.truncation = truncation;
      this.metadata = metadata;
      this.serviceTier = serviceTier;
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

    public static EmbedResponse fromBson(DocumentParser parser) throws BsonParseException {
      return new EmbedResponse(
          parser.getField(Fields.OBJECT_TYPE).unwrap(),
          parser.getField(Fields.DATA).unwrap(),
          parser.getField(Fields.EMBED_USAGE).unwrap());
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

    public static Vector decodeVectorFromBsonValue(BsonParseContext context, BsonValue value)
        throws BsonParseException {
      if (!value.isString()) {
        return context.handleUnexpectedType(TypeDescription.STRING, value.getBsonType());
      }
      String base64Vector = value.asString().getValue();
      Vector result;
      try {
        ByteBuffer byteBuffer =
            ByteBuffer.wrap(Base64.getDecoder().decode(base64Vector))
                .order(ByteOrder.LITTLE_ENDIAN);
        result = parseFloatVector(byteBuffer);
      } catch (Exception e) {
        // Not base64 or invalid proto format
        return context.handleSemanticError("Invalid format for token value");
      }
      return result;
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
}
