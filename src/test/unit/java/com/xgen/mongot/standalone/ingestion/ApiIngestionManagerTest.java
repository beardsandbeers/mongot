package com.xgen.mongot.standalone.ingestion;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.catalog.DefaultIndexCatalog;
import com.xgen.mongot.catalog.InitializedIndexCatalog;
import com.xgen.mongot.cursor.CursorConfig;
import com.xgen.mongot.cursor.CursorQuery;
import com.xgen.mongot.cursor.MongotCursorManagerImpl;
import com.xgen.mongot.cursor.batch.BatchCursorOptions;
import com.xgen.mongot.cursor.batch.QueryCursorOptions;
import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.featureflag.dynamic.DynamicFeatureFlagRegistry;
import com.xgen.mongot.index.EncodedUserData;
import com.xgen.mongot.index.IndexGeneration;
import com.xgen.mongot.index.analyzer.AnalyzerRegistry;
import com.xgen.mongot.index.definition.IndexDefinitionGenerationProducer;
import com.xgen.mongot.index.definition.SearchIndexDefinition;
import com.xgen.mongot.index.definition.VectorIndexDefinition;
import com.xgen.mongot.index.lucene.LuceneIndexFactory;
import com.xgen.mongot.index.lucene.directory.EnvironmentVariantPerfConfig;
import com.xgen.mongot.index.lucene.vector.Similarity;
import com.xgen.mongot.index.query.MaterializedVectorSearchQuery;
import com.xgen.mongot.index.query.QueryExecutionContext;
import com.xgen.mongot.index.query.QueryOptimizationFlags;
import com.xgen.mongot.index.query.SearchQuery;
import com.xgen.mongot.index.query.VectorSearchQuery;
import com.xgen.mongot.index.version.Generation;
import com.xgen.mongot.index.version.UserIndexVersion;
import com.xgen.mongot.metrics.MeterAndFtdcRegistry;
import com.xgen.mongot.monitor.NoOpDiskMonitor;
import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.grpc.CreateCollectionRequest;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.Mutation;
import com.xgen.mongot.standalone.grpc.MutationType;
import com.xgen.mongot.standalone.grpc.SequenceCondition;
import com.xgen.mongot.standalone.grpc.WriteRequest;
import com.xgen.mongot.standalone.store.StandaloneStore;
import com.xgen.mongot.util.Bytes;
import com.xgen.testing.mongot.index.lucene.LuceneConfigBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ApiIngestionManagerTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void commit_partitionZeroIoFailure_reopensFromOldestCommitAndReplaysInOrder()
      throws Exception {
    String indexId;
    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      var index = fixture.searchIndex("text", 4);
      indexId = index.getIndexId();
      fixture.write("first", MutationType.MUTATION_TYPE_INSERT, "{_id:'a',title:'old'}");
      fixture.write("replace", MutationType.MUTATION_TYPE_UPSERT, "{_id:'a',title:'replace'}");
      fixture.write("delete", MutationType.MUTATION_TYPE_DELETE, "{_id:'a'}");
      fixture.write("final", MutationType.MUTATION_TYPE_INSERT, "{_id:'a',title:'replayed'}");
      var generation =
          fixture.live.getIndexById(new org.bson.types.ObjectId(indexId)).orElseThrow();
      fixture.manager.dropIndex(generation.getGenerationId()).get(10, TimeUnit.SECONDS);
      var writer =
          fixture.initialized.getIndex(generation.getGenerationId()).orElseThrow().getWriter();
      var old = StandaloneIndexCommitUserData.decode(writer.getCommitUserData());
      var next =
          new StandaloneIndexCommitUserData(
              old.instanceId(),
              old.collectionId(),
              old.generationId(),
              old.definitionHash(),
              true,
              4);
      Path zero = partition(this.folder.getRoot().toPath(), indexId, "0");
      Path three = partition(this.folder.getRoot().toPath(), indexId, "3");
      Path obstruction = obstructNextCommit(zero);
      try {
        assertThrows(java.io.IOException.class, () -> writer.commit(next.encode()));

        assertThat(committedSequence(three)).isEqualTo(4);
        assertThat(committedSequence(zero)).isEqualTo(0);
      } finally {
        Files.delete(obstruction.resolve("obstruction"));
        Files.delete(obstruction);
        // FSDirectory remembers its failed delete. Leave an ordinary disposable file for its
        // rollback/close path to remove, instead of racing that bookkeeping with an absent path.
        Files.writeString(obstruction, "discard failed pending commit on writer close");
      }
    }
    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      var index = fixture.searchIndex("text", 4);
      fixture.visible(index, 4);

      assertThat(index.getIndexId()).isEqualTo(indexId);
      assertThat(fixture.search("text", "title", "old")).isEmpty();
      assertThat(fixture.search("text", "title", "replace")).isEmpty();
      assertThat(fixture.search("text", "title", "replayed")).hasSize(1);
    }
  }

  private static Path partition(Path root, String indexId, String ordinal) throws Exception {
    try (var paths = Files.walk(root.resolve("indexes"))) {
      return paths
          .filter(Files::isDirectory)
          .filter(
              path ->
                  path.getFileName().toString().equals(ordinal)
                      && path.getParent().getParent().equals(root.resolve("indexes"))
                      && path.getParent().getFileName().toString().startsWith(indexId))
          .findFirst()
          .orElseThrow();
    }
  }

  private static Path obstructNextCommit(Path partition) throws Exception {
    long current;
    try (var files = Files.list(partition)) {
      current =
          files
              .map(path -> path.getFileName().toString())
              .filter(name -> name.startsWith("segments_"))
              .mapToLong(name -> Long.parseLong(name.substring("segments_".length()), 36))
              .max()
              .orElseThrow();
    }
    Path obstruction = partition.resolve("pending_segments_" + Long.toString(current + 1, 36));
    Files.createDirectory(obstruction);
    Files.writeString(obstruction.resolve("obstruction"), "force an actual commit I/O failure");
    return obstruction;
  }

  private static long committedSequence(Path partition) throws Exception {
    try (var directory = FSDirectory.open(partition);
        var reader = DirectoryReader.open(directory)) {
      return StandaloneIndexCommitUserData.decode(
              EncodedUserData.fromString(reader.getIndexCommit().getUserData().get("userData")))
          .lastAppliedSequence();
    }
  }

  @Test
  public void captureTargets_previouslyReadyGenerationNotRegistered_blocksAcceptance()
      throws Exception {
    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      fixture.searchIndex("text", 1);
      var recovering =
          new ApiIngestionManager(
              fixture.store,
              fixture.live,
              fixture.initialized,
              Duration.ofSeconds(1),
              Duration.ofSeconds(1));

      var error =
          assertThrows(
              io.grpc.StatusRuntimeException.class,
              () -> recovering.captureTargets(fixture.collection));

      assertThat(error.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.UNAVAILABLE);
    }
  }

  @Test
  public void reopen_prunedSuffixOlderThanCheckpoint_rebuildsFullSourceBeforeReadiness()
      throws Exception {
    String indexId;
    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      var index = fixture.searchIndex("text", 1);
      indexId = index.getIndexId();
      fixture.write("source", MutationType.MUTATION_TYPE_INSERT, "{_id:'a',title:'rebuildterm'}");
      fixture
          .manager
          .dropIndex(
              fixture
                  .live
                  .getIndexById(new org.bson.types.ObjectId(indexId))
                  .orElseThrow()
                  .getGenerationId())
          .get(10, TimeUnit.SECONDS);
      fixture.store.maintain(0);
      assertThat(
              fixture.store.<Long>read(
                  transaction -> transaction.prunedThrough(fixture.collection)))
          .isEqualTo(1);
      assertThat(fixture.store.<Long>read(transaction -> transaction.logBytes())).isEqualTo(0);
    }
    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      var index = fixture.searchIndex("text", 1);
      fixture.visible(index, 1);

      assertThat(index.getIndexId()).isEqualTo(indexId);
      assertThat(fixture.search("text", "title", "rebuildterm")).hasSize(1);
    }
  }

  @Test
  public void apply_replaceAndDelete_updatesRealLuceneTermsAndStoredSource() throws Exception {
    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      var index = fixture.searchIndex("text", 1);
      fixture.write(
          "insert",
          MutationType.MUTATION_TYPE_INSERT,
          "{_id: 'a', title: 'oldterm', removed: true}");
      fixture.visible(index, 1);

      assertThat(fixture.search("text", "title", "oldterm")).hasSize(1);

      fixture.write("replace", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a', title: 'newterm'}");
      fixture.visible(index, 2);

      assertThat(fixture.search("text", "title", "oldterm")).isEmpty();
      var replacement = fixture.search("text", "title", "newterm");
      assertThat(replacement).hasSize(1);
      assertThat(replacement.get(0).asDocument().getDocument("storedSource"))
          .isEqualTo(BsonDocument.parse("{_id: 'a', title: 'newterm'}"));

      fixture.write("delete", MutationType.MUTATION_TYPE_DELETE, "{_id: 'a'}");
      fixture.visible(index, 3);

      assertThat(fixture.search("text", "title", "newterm")).isEmpty();
    }
  }

  @Test
  public void createVectorIndex_populatedCollection_buildsFromLocalSourceAndQueries()
      throws Exception {
    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      fixture.searchIndex("text", 1);
      fixture.write(
          "insert",
          MutationType.MUTATION_TYPE_INSERT,
          "{_id: 'a', title: 'historical', embedding: [1.0, 0.0, 0.0]}");

      var vector = fixture.vectorIndex("vector", 1);
      fixture.visible(vector, 1);

      var result = fixture.vector("vector");
      assertThat(result).hasSize(1);
      assertThat(result.get(0).asDocument().getString("_id").getValue()).isEqualTo("a");
    }
  }

  @Test
  public void apply_embeddedDocuments_replacementAndDeleteRemoveEntireDocumentBlocks()
      throws Exception {
    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      var index =
          fixture.addIndex(
              "embedded",
              IndexType.INDEX_TYPE_SEARCH,
              "{mappings: {dynamic: false, fields: {items: {type: 'embeddedDocuments', dynamic:"
                  + " true}}}, storedSource: true}");
      fixture.write(
          "insert",
          MutationType.MUTATION_TYPE_INSERT,
          "{_id: 'a', items: [{label: 'oldterm'}, {label: 'another'}]}");
      fixture.visible(index, 1);

      assertThat(fixture.embedded("oldterm")).hasSize(1);

      fixture.write(
          "replace", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a', items: [{label: 'newterm'}]}");
      fixture.visible(index, 2);

      assertThat(fixture.embedded("oldterm")).isEmpty();
      assertThat(fixture.embedded("newterm")).hasSize(1);

      fixture.write("delete", MutationType.MUTATION_TYPE_DELETE, "{_id: 'a'}");
      fixture.visible(index, 3);

      assertThat(fixture.embedded("newterm")).isEmpty();
    }
  }

  @Test
  public void reopen_multiPartitionProjection_replaysConservativeCheckpointAndRetainsIdentities()
      throws Exception {
    String indexId;
    String collectionId;
    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      var index = fixture.searchIndex("text", 4);
      indexId = index.getIndexId();
      collectionId = fixture.collection;
      fixture.write("first", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a', title: 'first'}");
      fixture.write("second", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a', title: 'second'}");
      fixture.write("delete", MutationType.MUTATION_TYPE_DELETE, "{_id: 'a'}");
      fixture.write("final", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a', title: 'finalterm'}");
      // Stop without publishing a newer checkpoint. Lucene close can persist data whose metadata
      // still says position zero; reopening must replay that conservative suffix in source order.
      fixture
          .manager
          .dropIndex(
              fixture
                  .live
                  .getIndexById(new org.bson.types.ObjectId(indexId))
                  .orElseThrow()
                  .getGenerationId())
          .get(10, TimeUnit.SECONDS);
    }

    try (var fixture = new Fixture(this.folder.getRoot().toPath())) {
      var index = fixture.searchIndex("text", 4);
      fixture.visible(index, 4);

      assertThat(index.getIndexId()).isEqualTo(indexId);
      assertThat(fixture.collection).isEqualTo(collectionId);
      assertThat(fixture.search("text", "title", "first")).isEmpty();
      assertThat(fixture.search("text", "title", "second")).isEmpty();
      assertThat(fixture.search("text", "title", "finalterm")).hasSize(1);
      assertThat(fixture.ledger.get("user", collectionId, "final").getComplete()).isTrue();
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final StandaloneStore store;
    private final LocalAuthoritativeCatalog catalog;
    private final DurableWriteLedger ledger;
    private final MeterAndFtdcRegistry metrics = MeterAndFtdcRegistry.createWithSimpleRegistries();
    private final DefaultIndexCatalog live = new DefaultIndexCatalog();
    private final InitializedIndexCatalog initialized = new InitializedIndexCatalog();
    private final LuceneIndexFactory factory;
    private final ApiIngestionManager manager;
    private final MongotCursorManagerImpl cursors;
    private final String collection;

    private Fixture(Path root) throws Exception {
      Similarity.load();
      this.store = new StandaloneStore(root);
      this.catalog = new LocalAuthoritativeCatalog(this.store);
      this.ledger = new DurableWriteLedger(this.store, DurableWriteLedger.Limits.defaults());
      this.collection =
          this.catalog
              .createCollection(
                  "user",
                  CreateCollectionRequest.newBuilder()
                      .setRequestId("collection")
                      .setDatabase("database")
                      .setName("collection")
                      .build())
              .getCollectionId();
      this.factory =
          LuceneIndexFactory.fromConfig(
              LuceneConfigBuilder.builder()
                  .dataPath(root.resolve("indexes"))
                  .refreshInterval(Duration.ofHours(1))
                  .refreshExecutorThreads(1)
                  .numMaxMergeThreads(1)
                  .numMaxMerges(2)
                  .ramBufferSizeMb(16)
                  .enableConcurrentSearch(false)
                  .build(),
              FeatureFlags.communityDefaults(),
              DynamicFeatureFlagRegistry.communityDefault(),
              EnvironmentVariantPerfConfig.getDefault(),
              this.metrics,
              Optional.empty(),
              AnalyzerRegistry.factory(),
              new NoOpDiskMonitor());
      this.manager =
          new ApiIngestionManager(
              this.store, this.live, this.initialized, Duration.ofHours(1), Duration.ofMillis(5));
      this.cursors =
          MongotCursorManagerImpl.fromConfig(
              CursorConfig.getDefault(), this.metrics.meterRegistry(), this.live, this.initialized);
    }

    private IndexDescription searchIndex(String name, int partitions) throws Exception {
      return addIndex(
          name,
          IndexType.INDEX_TYPE_SEARCH,
          "{numPartitions: " + partitions + ", mappings: {dynamic: true}, storedSource: true}");
    }

    private IndexDescription vectorIndex(String name, int partitions) throws Exception {
      return addIndex(
          name,
          IndexType.INDEX_TYPE_VECTOR_SEARCH,
          "{numPartitions: "
              + partitions
              + ", fields: [{type: 'vector', path: 'embedding', numDimensions: 3, similarity:"
              + " 'cosine'}]}");
    }

    private IndexDescription addIndex(String name, IndexType type, String json) throws Exception {
      var description =
          this.catalog.createIndex(
              "user",
              CreateIndexRequest.newBuilder()
                  .setRequestId("create-" + name)
                  .setCollectionId(this.collection)
                  .setName(name)
                  .setType(type)
                  .setDefinitionBson(bson(json))
                  .build());
      var record =
          this.store.read(transaction -> transaction.index(description.getIndexId())).orElseThrow();
      var definition = StandaloneDefinitionValidator.restore(record.normalizedDefinition());
      var producer =
          definition instanceof SearchIndexDefinition search
              ? IndexDefinitionGenerationProducer.createProducers(
                      List.of(), List.of(search), List.of())
                  .getFirst()
              : IndexDefinitionGenerationProducer.createProducers(
                      List.of((VectorIndexDefinition) definition), List.of(), List.of())
                  .getFirst();
      var definitionGeneration =
          producer.createIndexDefinitionGeneration(
              Generation.current(definition.engine(), UserIndexVersion.FIRST));
      var index = this.factory.getIndex(definitionGeneration);
      var initializedIndex = this.factory.getInitializedIndex(index, definitionGeneration);
      this.initialized.addIndex(initializedIndex);
      var generation = new IndexGeneration(index, definitionGeneration);
      this.live.addIndex(generation);
      this.manager.add(generation);
      this.manager
          .await(
              description.getIndexId(),
              generation.getGenerationId().uniqueString(),
              0,
              SequenceCondition.SEQUENCE_CONDITION_VISIBLE)
          .get(10, TimeUnit.SECONDS);
      return description;
    }

    private void write(String id, MutationType type, String json) throws Exception {
      var request =
          WriteRequest.newBuilder()
              .setCollectionId(this.collection)
              .setRequestId(id)
              .setMutation(Mutation.newBuilder().setType(type).setBson(bson(json)))
              .build();
      var manifest =
          this.manager.coordinate(
              () ->
                  this.ledger.begin("user", request, this.manager.captureTargets(this.collection)));
      var result =
          this.manager.coordinate(
              () -> {
                this.manager.validateTargets(manifest.targets());
                return this.ledger.acceptItem("user", this.collection, id, 0);
              });
      this.manager.wakeConsumers();
      this.manager
          .awaitTargets(manifest.targets(), result.getAcceptedSequence())
          .get(10, TimeUnit.SECONDS);
      this.ledger.finishItem("user", this.collection, id, 0, io.grpc.Status.OK);
    }

    private void visible(IndexDescription index, long sequence) throws Exception {
      var generation =
          this.live.getIndexById(new org.bson.types.ObjectId(index.getIndexId())).orElseThrow();
      this.manager
          .await(
              index.getIndexId(),
              generation.getGenerationId().uniqueString(),
              sequence,
              SequenceCondition.SEQUENCE_CONDITION_VISIBLE)
          .get(10, TimeUnit.SECONDS);
    }

    private BsonArray search(String index, String path, String text) throws Exception {
      var query =
          SearchQuery.fromBson(
              BsonDocument.parse(
                  "{index: '"
                      + index
                      + "', text: {path: '"
                      + path
                      + "', query: '"
                      + text
                      + "'}, returnStoredSource: true}"));
      return query(new CursorQuery.Search(query));
    }

    private BsonArray vector(String index) throws Exception {
      var query =
          VectorSearchQuery.fromBson(
              BsonDocument.parse(
                  "{index: '"
                      + index
                      + "', path: 'embedding', queryVector: [1.0, 0.0, 0.0], exact: true, limit:"
                      + " 1}"));
      return query(
          new CursorQuery.Vector(
              new MaterializedVectorSearchQuery(
                  query, query.criteria().queryVector().orElseThrow()),
              QueryExecutionContext.empty()));
    }

    private BsonArray embedded(String text) throws Exception {
      var query =
          SearchQuery.fromBson(
              BsonDocument.parse(
                  "{index: 'embedded', embeddedDocument: {path: 'items',"
                      + " operator: {text: {path: 'items.label', query: '"
                      + text
                      + "'}}}, returnStoredSource: true}"));
      return query(new CursorQuery.Search(query));
    }

    private BsonArray query(CursorQuery query) throws Exception {
      var cursor =
          this.cursors.newCursor(
              "database",
              "collection",
              UUID.fromString(this.collection),
              Optional.empty(),
              query,
              QueryCursorOptions.empty(),
              QueryOptimizationFlags.DEFAULT_OPTIONS,
              Optional.empty());
      try {
        return this.cursors
            .getNextBatch(cursor.cursorId, Bytes.ofBytes(1000000), BatchCursorOptions.empty())
            .batch
            .asArray();
      } finally {
        this.cursors.killCursor(cursor.cursorId);
      }
    }

    @Override
    public void close() throws Exception {
      this.manager.shutdown().get(10, TimeUnit.SECONDS);
      this.cursors.close();
      for (var index : this.initialized.getIndexes()) {
        index.close();
      }
      this.factory.close();
      this.store.close();
      this.metrics.meterRegistry().close();
      this.metrics.ftdcRegistry().close();
    }
  }

  private static ByteString bson(String json) {
    return ByteString.copyFrom(StandaloneDefinitionValidator.encode(BsonDocument.parse(json)));
  }
}
