package com.xgen.mongot.embedding.providers.configs;

import com.google.common.collect.ImmutableMap;
import com.xgen.mongot.util.bson.parser.BsonParseContext;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.TypeDescription;
import com.xgen.mongot.util.bson.parser.ValueEncoder;
import org.bson.BsonString;
import org.bson.BsonType;
import org.bson.BsonValue;

/**
 * Vector index-related defaults carried in MMS {@code modelConfig} for Voyage. {@code
 * modelConfig.quantization} uses MMS conf-call literals ({@code float}, {@code scalar}, …), not
 * index-definition {@code none}. This type lives in the embedding package to avoid a Maven/Bazel
 * cycle with {@code index.definition}.
 */
public final class VoyageModelVectorParams {

  private VoyageModelVectorParams() {}

  /**
   * Similarity values for map entries in MMS {@code modelConfig.similarity} (keys are typically
   * per-quantization buckets from MMS). Wire format matches vector field {@code similarity}
   * (camelCase enum string).
   */
  public enum Similarity {
    EUCLIDEAN,
    DOT_PRODUCT,
    COSINE
  }

  /**
   * Quantization modes aligned with {@link com.xgen.mongot.index.definition.VectorQuantization}.
   * Full-precision vectors are {@code NONE}; MMS conf call sends wire {@code "float"} for that
   * case. {@code "float"} as a {@code modelConfig.similarity} map key is a bucket label, not this
   * enum.
   */
  public enum Quantization {
    NONE, // full-precision float vector
    SCALAR,
    BINARY,
    BINARY_NO_RESCORE
  }

  /**
   * MMS conf-call literals for {@code modelConfig.quantization} (exact match; {@code NONE} is wire
   * {@code float}, not {@code none}).
   */
  private static final ImmutableMap<String, Quantization> QUANTIZATION_BY_CONF_CALL_LITERAL =
      ImmutableMap.of(
          "float", Quantization.NONE,
          "scalar", Quantization.SCALAR,
          "binary", Quantization.BINARY,
          "binaryNoRescore", Quantization.BINARY_NO_RESCORE);

  /**
   * BSON encoder for {@code modelConfig.quantization}; matches {@link
   * #QUANTIZATION_BY_CONF_CALL_LITERAL}.
   */
  public static final ValueEncoder<Quantization> QUANTIZATION_MODEL_CONFIG_WIRE_ENCODER =
      quantization ->
          new BsonString(
              switch (quantization) {
                case NONE -> "float";
                case SCALAR -> "scalar";
                case BINARY -> "binary";
                case BINARY_NO_RESCORE -> "binaryNoRescore";
              });

  /** Parses {@code modelConfig.quantization} from conf-call string values. */
  public static Quantization parseQuantizationFromModelConfigWire(
      BsonParseContext context, BsonValue value) throws BsonParseException {
    if (value.getBsonType() != BsonType.STRING) {
      context.handleUnexpectedType(TypeDescription.STRING, value.getBsonType());
    }
    String wire = value.asString().getValue();
    Quantization quantization = QUANTIZATION_BY_CONF_CALL_LITERAL.get(wire);
    if (quantization == null) {
      return context.handleSemanticError("must be one of [float, scalar, binary, binaryNoRescore]");
    }
    return quantization;
  }
}
