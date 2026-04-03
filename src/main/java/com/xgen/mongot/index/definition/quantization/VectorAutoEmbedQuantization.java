package com.xgen.mongot.index.definition.quantization;

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

/**
 * MMS / auto-embed index BSON {@code quantization} ({@code float}, {@code scalar}, {@code binary},
 * {@code binaryNoRescore}). {@link #toLuceneQuantization()} is the Lucene vector field quantization
 * for the {@code autoEmbed} text specification.
 *
 * <p>Only {@link #BINARY} maps to {@link VectorQuantization#BINARY}; all other provider values use
 * {@link VectorQuantization#NONE}.
 */
public enum VectorAutoEmbedQuantization {
  FLOAT("float"),
  SCALAR("scalar"),
  BINARY("binary"),
  BINARY_NO_RESCORE("binaryNoRescore");

  private static final ImmutableMap<String, VectorAutoEmbedQuantization> NAME_TO_QUANTIZATION;

  static {
    NAME_TO_QUANTIZATION =
        Arrays.stream(VectorAutoEmbedQuantization.values())
            .collect(
                ImmutableMap.toImmutableMap(
                    quantization -> quantization.name, Function.identity()));
  }

  private final String name;

  VectorAutoEmbedQuantization(String name) {
    this.name = name;
  }

  public String getName() {
    return this.name;
  }

  /** Provider bucket → Lucene {@link VectorQuantization} on the {@code autoEmbed} field spec. */
  public VectorQuantization toLuceneQuantization() {
    return this == BINARY ? VectorQuantization.BINARY : VectorQuantization.NONE;
  }

  /**
   * Approximate payload bytes for one stored embedding (memory budgeting): float32, one byte per
   * dimension for scalar, or packed bits for binary ({@code numDimensions} a multiple of 8).
   */
  public long estimatedEmbeddingPayloadBytes(int numDimensions) {
    return switch (this) {
      case FLOAT, BINARY -> (long) numDimensions * Float.BYTES;
      case SCALAR -> numDimensions;
      case BINARY_NO_RESCORE -> numDimensions / 8;
    };
  }

  public static Optional<VectorAutoEmbedQuantization> fromName(String name) {
    return Optional.ofNullable(NAME_TO_QUANTIZATION.get(name));
  }
}
