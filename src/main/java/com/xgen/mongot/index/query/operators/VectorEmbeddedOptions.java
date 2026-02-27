package com.xgen.mongot.index.query.operators;

import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentEncodable;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import org.bson.BsonDocument;

/**
 * Options for vector search queries on embedded vector fields.
 *
 * <p>When querying embedded vectors (vectors within array subdocuments), multiple child vectors may
 * match the query. The {@code scoreMode} determines how to aggregate scores from matching child
 * documents to compute a single score for the parent document.
 */
public record VectorEmbeddedOptions(ScoreMode scoreMode) implements DocumentEncodable {

  /**
   * Defines how to aggregate scores from multiple matching child documents.
   *
   * <ul>
   *   <li>{@code MAX} - Use the maximum score from all matching child documents
   *   <li>{@code AVG} - Use the average score from all matching child documents
   * </ul>
   */
  public enum ScoreMode {
    MAX,
    AVG
  }

  public static class Fields {
    public static final Field.WithDefault<ScoreMode> SCORE_MODE =
        Field.builder("scoreMode")
            .enumField(ScoreMode.class)
            .asCamelCase()
            .optional()
            .withDefault(ScoreMode.MAX);
  }

  public static VectorEmbeddedOptions fromBson(DocumentParser parser) throws BsonParseException {
    return new VectorEmbeddedOptions(parser.getField(Fields.SCORE_MODE).unwrap());
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder().field(Fields.SCORE_MODE, this.scoreMode).build();
  }
}

