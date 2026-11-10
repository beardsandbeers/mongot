package com.xgen.mongot.standalone.catalog;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.store.StandaloneStore.CollectionRecord;
import org.bson.BsonDocument;
import org.junit.Test;

public class StandaloneDefinitionValidatorTest {
  private static final CollectionRecord COLLECTION =
      new CollectionRecord("a5f61f66-6d7d-456c-a365-8bfc3d63cf1b", "database", "collection", 0);

  @Test
  public void normalize_explicitDefaultPartitions_matchesOmittedDefault() {
    var implicit = parse("{mappings: {dynamic: true}}", IndexType.INDEX_TYPE_SEARCH);
    var explicit =
        parse("{numPartitions: 1, mappings: {dynamic: true}}", IndexType.INDEX_TYPE_SEARCH);

    assertThat(implicit.normalizedUserDefinition()).isEqualTo(explicit.normalizedUserDefinition());
  }

  @Test
  public void normalize_explicitDefaultAnalyzers_matchesOmittedDefaults() {
    var implicit = parse("{mappings: {dynamic: true}}", IndexType.INDEX_TYPE_SEARCH);
    var explicit =
        parse(
            "{mappings: {dynamic: true}, analyzer: 'lucene.standard',"
                + " searchAnalyzer: 'lucene.standard', synonyms: []}",
            IndexType.INDEX_TYPE_SEARCH);

    assertThat(implicit.normalizedUserDefinition()).isEqualTo(explicit.normalizedUserDefinition());
  }

  @Test
  public void normalize_searchSortChange_remainsDistinct() {
    var ascending =
        parse(
            "{mappings: {dynamic: false, fields: {a: {type: 'number'}, b: {type: 'number'}}}, sort:"
                + " {a: 1, b: 1}}",
            IndexType.INDEX_TYPE_SEARCH);
    var descending =
        parse(
            "{mappings: {dynamic: false, fields: {a: {type: 'number'}, b: {type: 'number'}}}, sort:"
                + " {a: -1, b: 1}}",
            IndexType.INDEX_TYPE_SEARCH);

    assertThat(ascending.normalizedUserDefinition())
        .isNotEqualTo(descending.normalizedUserDefinition());
  }

  @Test
  public void normalize_searchSortPriorityChange_remainsDistinct() {
    var firstOrder =
        parse(
            "{mappings: {dynamic: false, fields: {a: {type: 'number'}, b: {type: 'number'}}}, sort:"
                + " {a: 1, b: 1}}",
            IndexType.INDEX_TYPE_SEARCH);
    var reversedOrder =
        parse(
            "{mappings: {dynamic: false, fields: {a: {type: 'number'}, b: {type: 'number'}}}, sort:"
                + " {b: 1, a: 1}}",
            IndexType.INDEX_TYPE_SEARCH);

    assertThat(firstOrder.normalizedUserDefinition())
        .isNotEqualTo(reversedOrder.normalizedUserDefinition());
  }

  @Test
  public void normalize_vectorStoredSourceChange_remainsDistinct() {
    var withoutSource = parse(vectorDefinition(false), IndexType.INDEX_TYPE_VECTOR_SEARCH);
    var withSource = parse(vectorDefinition(true), IndexType.INDEX_TYPE_VECTOR_SEARCH);

    assertThat(withoutSource.normalizedUserDefinition())
        .isNotEqualTo(withSource.normalizedUserDefinition());
  }

  @Test
  public void parse_mongoDbSynonymSource_rejects() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            parse(
                "{mappings: {dynamic: true}, synonyms: [{name: 'terms', analyzer:"
                    + " 'lucene.standard', source: {collection: 'synonyms'}}]}",
                IndexType.INDEX_TYPE_SEARCH));
  }

  @Test
  public void parse_unknownUserField_rejects() {
    assertThrows(
        IllegalArgumentException.class,
        () -> parse("{mappings: {dynamic: true}, unexpected: true}", IndexType.INDEX_TYPE_SEARCH));
  }

  @Test
  public void parse_unknownAnalyzerReference_rejects() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            parse("{mappings: {dynamic: true}, analyzer: 'absent'}", IndexType.INDEX_TYPE_SEARCH));
  }

  @Test
  public void restore_validInternalDefinition_preservesIdentityAndSemantics() {
    var definition =
        parse("{mappings: {dynamic: true}, storedSource: true}", IndexType.INDEX_TYPE_SEARCH);

    var restored =
        StandaloneDefinitionValidator.restore(
            StandaloneDefinitionValidator.encode(definition.internal().toBson()));

    assertThat(restored.getIndexId()).isEqualTo(definition.internal().getIndexId());
    assertThat(restored.getCollectionUuid()).isEqualTo(definition.internal().getCollectionUuid());
    assertThat(StandaloneDefinitionValidator.normalize(restored))
        .isEqualTo(definition.normalizedUserDefinition());
  }

  private static String vectorDefinition(boolean storedSource) {
    return "{fields: [{type: 'vector', path: 'embedding', numDimensions: 3, similarity: 'cosine'}],"
        + " storedSource: "
        + (storedSource ? "{include: ['category']}" : "false")
        + "}";
  }

  private static StandaloneDefinitionValidator.ValidatedDefinition parse(
      String json, IndexType type) {
    return StandaloneDefinitionValidator.parse(
        COLLECTION,
        "507f1f77bcf86cd799439011",
        "index",
        type,
        1,
        StandaloneDefinitionValidator.encode(BsonDocument.parse(json)));
  }
}
