package com.xgen.testing.mongot.integration.index.serialization;

import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentEncodable;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.BsonDocument;

public record VectorSearchTestVariant(
    Optional<VectorQuantization> quantization,
    Optional<Integer> partitions,
    Optional<Boolean> exactSearch)
    implements DocumentEncodable {

  private static final List<Integer> SUPPORTED_PARTITIONS_NUMBER = List.of(1, 2);

  public enum Features {
    QUANTIZATION,
    NUM_PARTITIONS,
    EXACT
  }

  static class Fields {
    static final Field.Optional<VectorQuantization> QUANTIZATION =
        Field.builder("quantization")
            .enumField(VectorQuantization.class)
            .asCamelCase()
            .optional()
            .noDefault();

    static final Field.Optional<Integer> NUM_PARTITIONS =
        Field.builder("numPartitions").intField().optional().noDefault();

    static final Field.Optional<Boolean> EXACT =
        Field.builder("exact").booleanField().optional().noDefault();
  }

  @SuppressWarnings("checkstyle:MissingJavadocMethod")
  public static List<VectorSearchTestVariant> resolvePossibleVariants(
      Set<Features> disabledFeatures) {
    List<Optional<VectorQuantization>> quantizationOptions =
        !disabledFeatures.contains(Features.QUANTIZATION)
            ? Arrays.stream(VectorQuantization.values()).map(Optional::of).toList()
            : List.of(Optional.empty());

    List<Optional<Integer>> partitionNumberOptions =
        !disabledFeatures.contains(Features.NUM_PARTITIONS)
            ? SUPPORTED_PARTITIONS_NUMBER.stream().map(Optional::of).toList()
            : List.of(Optional.empty());

    List<Optional<Boolean>> exactSearchOptions =
        !disabledFeatures.contains(Features.EXACT)
            ? Stream.of(true, false).map(Optional::of).toList()
            : List.of(Optional.empty());

    List<VectorSearchTestVariant> combinations = new ArrayList<>();

    for (Optional<VectorQuantization> quant : quantizationOptions) {
      for (Optional<Integer> partitionNumber : partitionNumberOptions) {
        for (Optional<Boolean> exact : exactSearchOptions) {
          combinations.add(new VectorSearchTestVariant(quant, partitionNumber, exact));
        }
      }
    }

    return combinations;
  }

  static VectorSearchTestVariant fromBson(DocumentParser parser) throws BsonParseException {
    var quantization = parser.getField(Fields.QUANTIZATION).unwrap();
    var numPartitions = parser.getField(Fields.NUM_PARTITIONS).unwrap();
    var exact = parser.getField(Fields.EXACT).unwrap();

    return new VectorSearchTestVariant(quantization, numPartitions, exact);
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(Fields.QUANTIZATION, this.quantization)
        .field(Fields.NUM_PARTITIONS, this.partitions)
        .field(Fields.EXACT, this.exactSearch)
        .build();
  }
}
