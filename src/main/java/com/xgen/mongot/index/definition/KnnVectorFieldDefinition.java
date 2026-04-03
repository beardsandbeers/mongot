package com.xgen.mongot.index.definition;

import static com.xgen.mongot.index.definition.VectorFieldSpecification.MAX_DIMENSIONS;

import com.xgen.mongot.index.definition.quantization.VectorQuantization;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import org.apache.commons.lang3.Range;
import org.bson.BsonDocument;

public record KnnVectorFieldDefinition(VectorFieldSpecification specification)
    implements FieldTypeDefinition {

  public KnnVectorFieldDefinition(int numDimensions, VectorSimilarity similarity) {
    this(
        new VectorFieldSpecification(
            numDimensions,
            similarity,
            VectorQuantization.NONE,
            new VectorIndexingAlgorithm.HnswIndexingAlgorithm()));
  }

  private static class Fields {
    private static final Field.Required<Integer> DIMENSIONS =
        Field.builder("dimensions")
            .intField()
            .mustBeWithinBounds(Range.of(1, MAX_DIMENSIONS))
            .required();
    private static final Field.Required<VectorSimilarity> SIMILARITY =
        Field.builder("similarity").enumField(VectorSimilarity.class).asCamelCase().required();
  }

  @Override
  public Type getType() {
    return Type.KNN_VECTOR;
  }

  @Override
  public BsonDocument fieldTypeToBson() {
    return BsonDocumentBuilder.builder()
        .field(Fields.DIMENSIONS, this.specification.numDimensions())
        .field(Fields.SIMILARITY, this.specification.similarity())
        .build();
  }

  static KnnVectorFieldDefinition fromBson(DocumentParser parser) throws BsonParseException {
    Integer dimensions = parser.getField(Fields.DIMENSIONS).unwrap();
    VectorSimilarity similarity = parser.getField(Fields.SIMILARITY).unwrap();
    return new KnnVectorFieldDefinition(dimensions, similarity);
  }
}
