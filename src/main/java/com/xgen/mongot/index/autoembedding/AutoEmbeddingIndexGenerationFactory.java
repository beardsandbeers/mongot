package com.xgen.mongot.index.autoembedding;

import static com.xgen.mongot.embedding.utils.AutoEmbeddingIndexDefinitionUtils.getDerivedVectorIndexDefinition;
import static com.xgen.mongot.index.definition.MaterializedViewIndexDefinitionGeneration.MIN_VERSION_FOR_MATERIALIZED_VIEW_EMBEDDING;
import static com.xgen.mongot.index.mongodb.MaterializedViewWriter.MV_DATABASE_NAME;

import com.xgen.mongot.catalog.InitializedIndexCatalog;
import com.xgen.mongot.embedding.config.MaterializedViewCollectionMetadata.MaterializedViewSchemaMetadata;
import com.xgen.mongot.index.Index;
import com.xgen.mongot.index.IndexFactory;
import com.xgen.mongot.index.InitializedVectorIndex;
import com.xgen.mongot.index.VectorIndex;
import com.xgen.mongot.index.analyzer.InvalidAnalyzerDefinitionException;
import com.xgen.mongot.index.definition.MaterializedViewIndexDefinitionGeneration;
import com.xgen.mongot.index.definition.VectorIndexDefinitionGeneration;
import com.xgen.mongot.index.version.MaterializedViewGeneration;
import com.xgen.mongot.util.Check;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Factory that generates composite AutoEmbedding index generation, used by ConfigManager to create
 * new IndexGeneration for any index lifecycle events (staged/live/recovered). Composite
 * IndexGeneration is used for easier index state management and status consolidation between
 * Materialized View and Lucene. For example, steady state Lucene on generationID:123_f1_u1_a1 could
 * be un-queryable if corresponding Materialized View status is in initial sync.
 */
public class AutoEmbeddingIndexGenerationFactory {

  /** Fetch a new AutoEmbeddingIndexGeneration */
  public static AutoEmbeddingIndexGeneration getAutoEmbeddingIndexGeneration(
      IndexFactory indexFactory,
      MaterializedViewIndexFactory matViewIndexFactory,
      VectorIndexDefinitionGeneration rawDefinitionGeneration,
      InitializedIndexCatalog initializedIndexCatalog)
      throws IOException, InvalidAnalyzerDefinitionException {
    Check.checkArg(
        rawDefinitionGeneration.getIndexDefinition().isAutoEmbeddingIndex()
            && rawDefinitionGeneration.getIndexDefinition().getParsedAutoEmbeddingFeatureVersion()
                >= MIN_VERSION_FOR_MATERIALIZED_VIEW_EMBEDDING,
        "Input definition is not materialized view based vector index");
    InitializedMaterializedViewIndex matViewIndex =
        matViewIndexFactory.getIndex(
            createMaterializedViewIndexDefinitionGeneration(rawDefinitionGeneration));
    VectorIndexDefinitionGeneration derivedIndexDefinitionGeneration =
        derivedIndexDefinitionGeneration(
            rawDefinitionGeneration, matViewIndex.getMaterializedViewCollectionUuid());
    Index vectorIndex = indexFactory.getIndex(derivedIndexDefinitionGeneration);
    // Supplier that looks up initialized index from catalog
    Supplier<Optional<InitializedVectorIndex>> initializedIndexSupplier =
        () ->
            initializedIndexCatalog
                .getIndex(derivedIndexDefinitionGeneration.getGenerationId())
                .filter(idx -> idx instanceof InitializedVectorIndex)
                .map(idx -> (InitializedVectorIndex) idx);
    return new AutoEmbeddingIndexGeneration(
        new AutoEmbeddingCompositeIndex(
            matViewIndex,
            Check.instanceOf(vectorIndex, VectorIndex.class),
            initializedIndexSupplier),
        rawDefinitionGeneration,
        derivedIndexDefinitionGeneration);
  }

  static MaterializedViewIndexDefinitionGeneration createMaterializedViewIndexDefinitionGeneration(
      VectorIndexDefinitionGeneration rawDefinitionGeneration) {
    return new MaterializedViewIndexDefinitionGeneration(
        rawDefinitionGeneration.getIndexDefinition(),
        new MaterializedViewGeneration(rawDefinitionGeneration.generation()));
  }

  private static VectorIndexDefinitionGeneration derivedIndexDefinitionGeneration(
      VectorIndexDefinitionGeneration rawDefinitionGeneration,
      UUID materializedViewCollectionUuid) {
    return new VectorIndexDefinitionGeneration(
        getDerivedVectorIndexDefinition(
            rawDefinitionGeneration.getIndexDefinition(),
            MV_DATABASE_NAME,
            materializedViewCollectionUuid,
            // TODO(CLOUDP-363914): Get MaterializedViewSchemaMetadata from Lease.
            new MaterializedViewSchemaMetadata(0, Map.of())),
        rawDefinitionGeneration.generation());
  }
}
