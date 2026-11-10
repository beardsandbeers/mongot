package com.xgen.mongot.standalone.catalog;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.config.util.Invariants;
import com.xgen.mongot.index.analyzer.AnalyzerRegistry;
import com.xgen.mongot.index.analyzer.InvalidAnalyzerDefinitionException;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.index.definition.InvalidIndexDefinitionException;
import com.xgen.mongot.index.definition.SearchIndexDefinition;
import com.xgen.mongot.index.definition.VectorIndexDefinition;
import com.xgen.mongot.index.version.IndexEngine;
import com.xgen.mongot.server.command.management.definition.common.UserIndexDefinition;
import com.xgen.mongot.server.command.management.util.IndexMapper;
import com.xgen.mongot.standalone.StandaloneBson;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.store.StandaloneStore.CollectionRecord;
import com.xgen.mongot.util.bson.parser.BsonDocumentParser;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.types.ObjectId;

/**
 * Shares the ordinary user mapping parser and validation without MongoDB management side effects.
 */
public final class StandaloneDefinitionValidator {
  private static final int MAX_DEFINITION_BYTES = 16 * 1024 * 1024;
  private static final int MAX_BSON_DEPTH = 100;

  public record ValidatedDefinition(IndexDefinition internal, byte[] normalizedUserDefinition) {}

  private StandaloneDefinitionValidator() {}

  /**
   * Parses public mappings, assigns local identity, and checks the standalone feature allowlist.
   */
  public static ValidatedDefinition parse(
      CollectionRecord collection,
      String indexId,
      String name,
      IndexType type,
      long revision,
      byte[] bson) {
    if (type != IndexType.INDEX_TYPE_SEARCH && type != IndexType.INDEX_TYPE_VECTOR_SEARCH) {
      throw new IllegalArgumentException("An explicit supported index type is required");
    }
    if (revision < 1 || revision > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Index revision is outside the supported range");
    }
    if (name.isBlank() || name.length() > 256 || name.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Index name must contain 1 to 256 characters without NUL");
    }
    try (var parser =
        BsonDocumentParser.fromRoot(
                StandaloneBson.document(bson, MAX_DEFINITION_BYTES, MAX_BSON_DEPTH))
            .build()) {
      var user =
          UserIndexDefinition.fromBson(
              parser,
              type == IndexType.INDEX_TYPE_SEARCH
                  ? IndexDefinition.Type.SEARCH
                  : IndexDefinition.Type.VECTOR_SEARCH);
      var internal =
          IndexMapper.toInternal(
              name,
              Optional.of(new ObjectId(indexId)),
              user,
              UUID.fromString(collection.id()),
              collection.database(),
              collection.name(),
              Optional.empty(),
              revision,
              Instant.now(),
              Optional.empty());
      validate(internal);
      return new ValidatedDefinition(internal, normalize(internal));
    } catch (BsonParseException e) {
      throw new IllegalArgumentException("Invalid index definition: " + e.getMessage(), e);
    }
  }

  public static IndexDefinition restore(byte[] internalBson) {
    try (var parser =
        BsonDocumentParser.fromRoot(
                StandaloneBson.document(internalBson, MAX_DEFINITION_BYTES, MAX_BSON_DEPTH))
            .build()) {
      var definition = IndexDefinition.fromBson(parser);
      validate(definition);
      return definition;
    } catch (BsonParseException e) {
      throw new IllegalArgumentException("Unreadable durable index definition", e);
    }
  }

  public static void validateCatalog(List<IndexDefinition> definitions) {
    try {
      Invariants.validateInvariants(
          List.of(),
          definitions.stream()
              .filter(SearchIndexDefinition.class::isInstance)
              .map(SearchIndexDefinition.class::cast)
              .toList(),
          definitions.stream()
              .filter(VectorIndexDefinition.class::isInstance)
              .map(VectorIndexDefinition.class::cast)
              .toList(),
          List.of());
    } catch (Invariants.InvariantException e) {
      throw new IllegalArgumentException("Invalid standalone catalog: " + e.getMessage(), e);
    }
  }

  private static void validate(IndexDefinition definition) {
    if (definition.getView().isPresent()
        || definition.isAutoEmbeddingIndex()
        || definition.engine() != IndexEngine.LUCENE) {
      throw new IllegalArgumentException(
          "Standalone supports Lucene text and client-provided vectors; views, auto-embedding"
              + " and custom engines are unsupported");
    }
    try {
      if (definition instanceof SearchIndexDefinition search) {
        if (!search.getSynonyms().orElse(List.of()).isEmpty()) {
          throw new IllegalArgumentException(
              "MongoDB synonym sources are unsupported in standalone");
        }
        search.validateEmbeddedDocumentsNestingLevel(5);
        search.validateSortDefinition();
        Invariants.AnalyzerInvariants.validate(List.of(search), List.of());
        Invariants.AnalyzerInvariants.validateFieldAnalyzerReferences(search, Set.of(), Set.of());
        for (var analyzer : search.getAnalyzers()) {
          AnalyzerRegistry.validateAnalyzerDefinition(analyzer);
        }
      } else {
        Invariants.validateVectorNestedRootReferences(List.of(definition.asVectorDefinition()));
      }
    } catch (InvalidIndexDefinitionException
        | InvalidAnalyzerDefinitionException
        | Invariants.InvariantException e) {
      throw new IllegalArgumentException("Invalid standalone mapping: " + e.getMessage(), e);
    }
  }

  /** Complete user semantics, including sort, stored source, nested root and relevant defaults. */
  public static byte[] normalize(IndexDefinition definition) {
    var external = IndexMapper.toExternal(definition).toBson();
    if (definition instanceof SearchIndexDefinition search) {
      String analyzer =
          search.getAnalyzerName().orElse(SearchIndexDefinition.DEFAULT_FALLBACK_ANALYZER);
      external.put("analyzer", new BsonString(analyzer));
      external.put(
          "searchAnalyzer", new BsonString(search.getSearchAnalyzerName().orElse(analyzer)));
      // Nonempty synonym sources are rejected by the standalone allowlist.
      external.remove("synonyms");
    }
    // Vector field ordering has no indexing semantics; analyzer/token-filter array ordering does.
    if (definition instanceof VectorIndexDefinition && external.containsKey("fields")) {
      external.put(
          "fields",
          new BsonArray(
              external.getArray("fields").getValues().stream()
                  .sorted(
                      Comparator.comparing(
                          value -> value.asDocument().getString("path").getValue()))
                  .toList()));
    }
    return encode(canonicalize(external, false).asDocument());
  }

  private static BsonValue canonicalize(BsonValue value, boolean orderedDocument) {
    if (value.isDocument()) {
      var result = new BsonDocument();
      @Var var entries = value.asDocument().entrySet().stream();
      if (!orderedDocument) {
        entries = entries.sorted(java.util.Map.Entry.comparingByKey());
      }
      entries.forEach(
          entry ->
              result.put(
                  entry.getKey(), canonicalize(entry.getValue(), entry.getKey().equals("sort"))));
      return result;
    }
    if (value.isArray()) {
      return new BsonArray(
          value.asArray().getValues().stream().map(item -> canonicalize(item, false)).toList());
    }
    return value;
  }

  public static byte[] encode(BsonDocument document) {
    var buffer = new RawBsonDocument(document, new BsonDocumentCodec()).getByteBuffer();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }
}
