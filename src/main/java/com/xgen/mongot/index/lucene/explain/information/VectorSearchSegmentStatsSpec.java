package com.xgen.mongot.index.lucene.explain.information;

import com.xgen.mongot.index.lucene.explain.timing.QueryExecutionArea;
import com.xgen.mongot.util.Equality;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Encodable;
import com.xgen.mongot.util.bson.parser.Field;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.collections4.Equator;
import org.bson.BsonValue;

public record VectorSearchSegmentStatsSpec(
    Optional<String> id,
    SegmentExecutionType executionType,
    int docCount,
    long visitedDocCount,
    Optional<QueryExecutionArea> approximateStage,
    Optional<QueryExecutionArea> exactStage,
    Optional<Integer> filterMatchedDocsCount)
    implements Encodable, EqualsWithTimingEquator<VectorSearchSegmentStatsSpec> {

  public enum SegmentExecutionType {
    APPROXIMATE,
    EXACT,
    FULL_SCAN_HEURISTIC,
    APPROXIMATE_FALLBACK_TO_EXACT
  }

  private static class Fields {
    static final Field.Optional<String> SEGMENT_ID =
        Field.builder("id").stringField().optional().noDefault();

    static final Field.Required<SegmentExecutionType> EXECUTION_TYPE =
        Field.builder("executionType")
            .enumField(SegmentExecutionType.class)
            .asUpperCamelCase()
            .required();

    static final Field.Required<Integer> DOC_COUNT =
        Field.builder("docCount").intField().required();

    static final Field.WithDefault<Long> VISITED_DOC_COUNT =
        Field.builder("visitedDocCount").longField().optional().withDefault(0L);

    static final Field.Optional<QueryExecutionArea> APPROXIMATE_STAGE =
        Field.builder("approximateStage")
            .classField(QueryExecutionArea::fromBson)
            .disallowUnknownFields()
            .optional()
            .noDefault();

    static final Field.Optional<QueryExecutionArea> EXACT_STAGE =
        Field.builder("exactStage")
            .classField(QueryExecutionArea::fromBson)
            .disallowUnknownFields()
            .optional()
            .noDefault();

    static final Field.Optional<Integer> FILTER_MATCHED_DOCS_COUNT =
        Field.builder("filterMatchedDocsCount").intField().optional().noDefault();
  }

  public static VectorSearchSegmentStatsSpec fromBson(DocumentParser parser)
      throws BsonParseException {
    return new VectorSearchSegmentStatsSpec(
        parser.getField(Fields.SEGMENT_ID).unwrap(),
        parser.getField(Fields.EXECUTION_TYPE).unwrap(),
        parser.getField(Fields.DOC_COUNT).unwrap(),
        parser.getField(Fields.VISITED_DOC_COUNT).unwrap(),
        parser.getField(Fields.APPROXIMATE_STAGE).unwrap(),
        parser.getField(Fields.EXACT_STAGE).unwrap(),
        parser.getField(Fields.FILTER_MATCHED_DOCS_COUNT).unwrap());
  }

  @Override
  public BsonValue toBson() {
    return BsonDocumentBuilder.builder()
        .field(Fields.SEGMENT_ID, this.id)
        .field(Fields.EXECUTION_TYPE, this.executionType)
        .field(Fields.DOC_COUNT, this.docCount)
        .fieldOmitDefaultValue(Fields.VISITED_DOC_COUNT, this.visitedDocCount)
        .field(Fields.APPROXIMATE_STAGE, this.approximateStage)
        .field(Fields.EXACT_STAGE, this.exactStage)
        .field(Fields.FILTER_MATCHED_DOCS_COUNT, this.filterMatchedDocsCount)
        .build();
  }

  @Override
  public boolean equals(
      VectorSearchSegmentStatsSpec other, Equator<QueryExecutionArea> timingEquator) {
    return Objects.equals(this.id, other.id)
        && Objects.equals(this.executionType, other.executionType)
        && Objects.equals(this.docCount, other.docCount)
        && Objects.equals(this.visitedDocCount, other.visitedDocCount)
        && Equality.equals(this.filterMatchedDocsCount, other.filterMatchedDocsCount)
        && Equality.equals(this.approximateStage, other.approximateStage, timingEquator)
        && Equality.equals(this.exactStage, other.exactStage, timingEquator);
  }
}
