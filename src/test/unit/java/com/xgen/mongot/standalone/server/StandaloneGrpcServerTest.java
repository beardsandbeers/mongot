package com.xgen.mongot.standalone.server;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.index.lucene.vector.Similarity;
import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.grpc.AwaitSequenceRequest;
import com.xgen.mongot.standalone.grpc.CatalogServiceGrpc;
import com.xgen.mongot.standalone.grpc.CreateCollectionRequest;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DocumentServiceGrpc;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.GetCollectionRequest;
import com.xgen.mongot.standalone.grpc.GetIndexRequest;
import com.xgen.mongot.standalone.grpc.GetMoreRequest;
import com.xgen.mongot.standalone.grpc.GetStatusRequest;
import com.xgen.mongot.standalone.grpc.GetWriteResultRequest;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.KillCursorRequest;
import com.xgen.mongot.standalone.grpc.ListIndexesRequest;
import com.xgen.mongot.standalone.grpc.Mutation;
import com.xgen.mongot.standalone.grpc.MutationType;
import com.xgen.mongot.standalone.grpc.OperationsServiceGrpc;
import com.xgen.mongot.standalone.grpc.QueryRequest;
import com.xgen.mongot.standalone.grpc.QueryResponse;
import com.xgen.mongot.standalone.grpc.QueryServiceGrpc;
import com.xgen.mongot.standalone.grpc.SequenceCondition;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.grpc.WriteBatchRequest;
import com.xgen.mongot.standalone.grpc.WriteItemState;
import com.xgen.mongot.standalone.grpc.WriteRequest;
import com.xgen.mongot.standalone.grpc.WriteResponse;
import com.xgen.mongot.standalone.runtime.StandaloneRuntime;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.bson.BsonDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StandaloneGrpcServerTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void restart_interruptedSnapshot_rebuildsIncompleteGenerationWithoutPrematureCutover()
      throws Exception {
    String indexId;
    var armed = new java.util.concurrent.atomic.AtomicBoolean();
    var config = StandaloneConfig.development(this.folder.getRoot().toPath(), 0);
    try (var fixture = new Fixture(config, unused -> {}, () -> failSnapshotIfArmed(armed))) {
      String collection = fixture.collection();
      indexId = fixture.index(collection).getIndexId();
      fixture
          .documents()
          .write(
              WriteRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("snapshot-source")
                  .setMutation(
                      mutation(MutationType.MUTATION_TYPE_INSERT, "{_id:'a',title:'historical'}"))
                  .build());
      armed.set(true);
      fixture
          .catalog()
          .updateIndex(
              UpdateIndexRequest.newBuilder()
                  .setIndexId(indexId)
                  .setExpectedRevision(1)
                  .setRequestId("snapshot-revision")
                  .setDefinitionBson(bson("{mappings:{dynamic:true},storedSource:true}"))
                  .build());
      var failed = fixture.indexFailure(indexId);

      assertThat(failed.getActiveRevision()).isEqualTo(1);
      assertThat(failed.getDesiredRevision()).isEqualTo(2);
      assertThat(results(fixture.search(indexId, "{text:{path:'title',query:'historical'}}")))
          .hasSize(1);
    }
    try (var fixture = new Fixture(config)) {
      var recovered = fixture.readyRevision(indexId, 2);
      fixture.visible(recovered, 1);

      var response =
          fixture.search(
              indexId, "{text:{path:'title',query:'historical'},returnStoredSource:true}");
      assertThat(results(response)).hasSize(1);
      assertThat(results(response).get(0).asDocument().getDocument("storedSource"))
          .isEqualTo(BsonDocument.parse("{_id:'a',title:'historical'}"));
    }
  }

  private static void failSnapshotIfArmed(java.util.concurrent.atomic.AtomicBoolean armed) {
    if (armed.get()) {
      throw new IllegalStateException(
          "Injected interruption after durable incomplete-build checkpoint");
    }
  }

  @Test
  public void reconcile_unreadableDesiredDefinition_preservesLiveProjectionAndStopsAdmission()
      throws Exception {
    var config = StandaloneConfig.development(this.folder.getRoot().toPath(), 0);
    try (var fixture = new Fixture(config)) {
      String collection = fixture.collection();
      var index = fixture.index(collection);
      executeSql(config.dataPath(), "UPDATE indexes SET normalized_definition=x'0000'");
      fixture.runtime.catalogChanged();
      fixture.managementFailure();

      assertThat(
              fixture
                  .runtime
                  .liveIndexes()
                  .getIndexById(new org.bson.types.ObjectId(index.getIndexId())))
          .isPresent();
      var rejected =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  fixture
                      .documents()
                      .write(
                          WriteRequest.newBuilder()
                              .setCollectionId(collection)
                              .setRequestId("unreadable-desired")
                              .setMutation(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id:'a'}"))
                              .build()));
      assertThat(rejected.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(
              fixture
                  .catalog()
                  .getCollection(
                      GetCollectionRequest.newBuilder().setCollectionId(collection).build())
                  .getAcceptedSequence())
          .isEqualTo(0);
    }
  }

  @Test
  public void writeBatch_targetFailure_stopsContiguousProgressAndRebuildsAcceptedSource()
      throws Exception {
    var blocker = new ApplyBlocker();
    try (var fixture =
        new Fixture(
            StandaloneConfig.development(this.folder.getRoot().toPath(), 0),
            sequence -> failSecondApply(sequence, blocker))) {
      String collection = fixture.collection();
      var index = fixture.index(collection);
      var request =
          WriteBatchRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("failed-target")
              .addMutations(
                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id:'a',title:'retained'}"))
              .addMutations(
                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id:'b',title:'retained'}"))
              .addMutations(
                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id:'c',title:'retained'}"))
              .build();
      var pending = DocumentServiceGrpc.newFutureStub(fixture.channel).writeBatch(request);
      blocker.awaitEntered();
      fixture.acceptedItems(collection, request.getRequestId(), 3);

      blocker.release();
      var failed = pending.get(10, TimeUnit.SECONDS);

      assertThat(failed.getResults(0).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXED);
      assertThat(failed.getResults(1).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXING_FAILED);
      assertThat(failed.getResults(2).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXING_FAILED);
      var stopped =
          fixture
              .catalog()
              .getIndex(GetIndexRequest.newBuilder().setIndexId(index.getIndexId()).build());
      assertThat(stopped.getLive().getAppliedSequence()).isEqualTo(1);
      assertThat(stopped.getFailureMessage()).contains("sequence 1");
      fixture
          .catalog()
          .updateIndex(
              UpdateIndexRequest.newBuilder()
                  .setIndexId(index.getIndexId())
                  .setExpectedRevision(1)
                  .setRequestId("recover-failed-target")
                  .setDefinitionBson(bson("{mappings:{dynamic:true},storedSource:true}"))
                  .build());
      var recovered = fixture.readyRevision(index.getIndexId(), 2);
      fixture.visible(recovered, 3);

      assertThat(
              results(fixture.search(index.getIndexId(), "{text:{path:'title',query:'retained'}}")))
          .hasSize(3);
      assertThat(fixture.documents().writeBatch(request)).isEqualTo(failed);
    } finally {
      blocker.release();
    }
  }

  private static void failSecondApply(long sequence, ApplyBlocker blocker) {
    if (sequence == 2) {
      blocker.accept(sequence);
      throw new IllegalStateException("Injected target apply failure");
    }
  }

  @Test
  public void write_sqliteAcceptanceFailure_rollsBackSourceAndResumesSameManifest()
      throws Exception {
    var config = StandaloneConfig.development(this.folder.getRoot().toPath(), 0);
    try (var fixture = new Fixture(config)) {
      String collection = fixture.collection();
      fixture.index(collection);
      var request =
          WriteRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("sqlite-failure")
              .setMutation(
                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id:'a',title:'recoverable'}"))
              .build();
      executeSql(
          config.dataPath(),
          "CREATE TRIGGER reject_mutation BEFORE INSERT ON mutations"
              + " BEGIN SELECT RAISE(ABORT, 'injected SQLite acceptance failure'); END");

      var failed =
          assertThrows(StatusRuntimeException.class, () -> fixture.documents().write(request));

      assertThat(failed.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(
              fixture
                  .catalog()
                  .getCollection(
                      GetCollectionRequest.newBuilder().setCollectionId(collection).build())
                  .getAcceptedSequence())
          .isEqualTo(0);
      var interrupted =
          fixture
              .documents()
              .getWriteResult(
                  GetWriteResultRequest.newBuilder()
                      .setCollectionId(collection)
                      .setRequestId(request.getRequestId())
                      .build());
      assertThat(interrupted.getComplete()).isFalse();
      assertThat(interrupted.getResultsList()).isEmpty();

      executeSql(config.dataPath(), "DROP TRIGGER reject_mutation");
      var recovered = fixture.documents().write(request);
      assertThat(recovered.getResults(0).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXED);
      assertThat(recovered.getResults(0).getAcceptedSequence()).isEqualTo(1);
    }
  }

  private static void executeSql(Path data, String sql) throws Exception {
    try (var connection =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + data.resolve("standalone.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout=5000");
      statement.execute(sql);
    }
  }

  @Test
  public void write_diskHeadroomExhausted_rejectsBeforePersistingManifestOrSource()
      throws Exception {
    var base = StandaloneConfig.development(this.folder.getRoot().toPath(), 0);
    var config =
        new StandaloneConfig(
            base.dataPath(),
            base.address(),
            base.documentLimits(),
            base.maxInFlightRequests(),
            base.maxInFlightBytes(),
            base.maxLogBytes(),
            Long.MAX_VALUE,
            base.commitInterval(),
            base.refreshInterval(),
            base.retryRetention(),
            base.maxRpcDuration(),
            base.tls(),
            base.principals());
    try (var fixture = new Fixture(config)) {
      String collection = fixture.collection();
      fixture.index(collection);

      var error =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  fixture
                      .documents()
                      .write(
                          WriteRequest.newBuilder()
                              .setCollectionId(collection)
                              .setRequestId("disk")
                              .setMutation(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id:'a'}"))
                              .build()));

      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(
              fixture
                  .catalog()
                  .getCollection(
                      GetCollectionRequest.newBuilder().setCollectionId(collection).build())
                  .getAcceptedSequence())
          .isEqualTo(0);
      var absent =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  fixture
                      .documents()
                      .getWriteResult(
                          GetWriteResultRequest.newBuilder()
                              .setCollectionId(collection)
                              .setRequestId("disk")
                              .build()));
      assertThat(absent.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }
  }

  @Test
  public void update_pinnedSnapshot_catchesConcurrentReplacementAndDeleteBeforeCutover()
      throws Exception {
    var snapshot = new SnapshotBlocker();
    try (var fixture =
        new Fixture(
            StandaloneConfig.development(this.folder.getRoot().toPath(), 0),
            unused -> {},
            snapshot)) {
      String collection = fixture.collection();
      String id =
          fixture
              .catalog()
              .createIndex(
                  indexRequest(collection).toBuilder()
                      .setDefinitionBson(
                          bson(
                              "{mappings: {dynamic: false, fields: {title: {type: 'string'}}},"
                                  + " storedSource: true}"))
                      .build())
              .getIndexId();
      var original = fixture.ready(id);
      fixture
          .documents()
          .writeBatch(
              WriteBatchRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("seed-build")
                  .addMutations(
                      mutation(
                          MutationType.MUTATION_TYPE_INSERT,
                          "{_id:'a', title:'old', next:'original'}"))
                  .addMutations(
                      mutation(
                          MutationType.MUTATION_TYPE_INSERT,
                          "{_id:'b', title:'old', next:'deleted'}"))
                  .build());
      snapshot.arm();
      fixture
          .catalog()
          .updateIndex(
              UpdateIndexRequest.newBuilder()
                  .setIndexId(id)
                  .setRequestId("mapping-build")
                  .setExpectedRevision(1)
                  .setDefinitionBson(
                      bson(
                          "{mappings: {dynamic: false, fields: {title: {type:'string'}, next:"
                              + " {type:'string'}}}, storedSource: true}"))
                  .build());
      snapshot.awaitEntered();
      var during = fixture.catalog().getIndex(GetIndexRequest.newBuilder().setIndexId(id).build());
      assertThat(during.getActiveRevision()).isEqualTo(1);
      assertThat(during.getLive().getGenerationId())
          .isEqualTo(original.getLive().getGenerationId());
      assertThat(during.hasBuilding()).isTrue();

      fixture
          .documents()
          .writeBatch(
              WriteBatchRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("during-build")
                  .addMutations(
                      mutation(
                          MutationType.MUTATION_TYPE_UPSERT,
                          "{_id:'a', title:'replacement', next:'changed'}"))
                  .addMutations(mutation(MutationType.MUTATION_TYPE_DELETE, "{_id:'b'}"))
                  .build());
      fixture.runtime.store().maintain(0);
      fixture.visible(original, 4);
      var oldResults =
          fixture
              .queries()
              .search(
                  QueryRequest.newBuilder()
                      .setIndexId(id)
                      .setQueryBson(
                          bson("{text:{path:'title',query:'replacement'},returnStoredSource:true}"))
                      .build());
      assertThat(results(oldResults)).hasSize(1);

      snapshot.release();
      var replacement = fixture.readyRevision(id, 2);
      fixture.visible(replacement, 4);
      var changed =
          fixture
              .queries()
              .search(
                  QueryRequest.newBuilder()
                      .setIndexId(id)
                      .setQueryBson(
                          bson("{text:{path:'next',query:'changed'},returnStoredSource:true}"))
                      .build());
      var deleted =
          fixture
              .queries()
              .search(
                  QueryRequest.newBuilder()
                      .setIndexId(id)
                      .setQueryBson(bson("{text:{path:'next',query:'deleted'}}"))
                      .build());

      assertThat(replacement.getLive().getGenerationId())
          .isNotEqualTo(original.getLive().getGenerationId());
      assertThat(results(changed)).hasSize(1);
      assertThat(results(deleted)).isEmpty();
    } finally {
      snapshot.release();
    }
  }

  @Test
  public void operations_status_reportsLocalWatermarksStorageAndMetrics() throws Exception {
    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0))) {
      String collection = fixture.collection();
      var index = fixture.index(collection);
      var request =
          WriteRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("metrics")
              .setMutation(
                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'metrics', title: 'term'}"))
              .build();
      fixture.documents().write(request);
      fixture.documents().write(request);

      var status =
          OperationsServiceGrpc.newBlockingStub(fixture.channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .getStatus(GetStatusRequest.getDefaultInstance());

      assertThat(status.getManagementReady()).isTrue();
      assertThat(status.getStoreSchemaVersion()).isEqualTo(2);
      assertThat(status.getInstanceId()).isNotEmpty();
      assertThat(status.getRetryRequests()).isAtLeast(3);
      assertThat(status.getRetentionPins()).isAtLeast(1);
      assertThat(status.getSourceBytes()).isGreaterThan(0);
      assertThat(
              status.getIndexesList().stream()
                  .filter(value -> value.getIndexId().equals(index.getIndexId()))
                  .findFirst()
                  .orElseThrow()
                  .getLive()
                  .getAppliedSequence())
          .isEqualTo(1);
      assertThat(status.getMetricsList().stream().map(value -> value.getName()).toList())
          .containsAtLeast(
              "standalone.mutations",
              "standalone.write.retries",
              "standalone.rpc.inflight",
              "standalone.rpc.duration",
              "standalone.acceptance",
              "standalone.apply");
      assertThat(
              status.getMetricsList().stream()
                  .flatMap(value -> value.getLabelsMap().values().stream())
                  .toList())
          .doesNotContain("metrics");
    }
  }

  @Test
  public void tls_authenticatedTransport_servesAuthorizedCatalogAndRejectsMissingToken()
      throws Exception {
    var base = StandaloneConfig.development(this.folder.getRoot().toPath(), 0);
    var tls = generateTls(this.folder.newFolder("tls").toPath());
    var config =
        new StandaloneConfig(
            base.dataPath(),
            base.address(),
            base.documentLimits(),
            base.maxInFlightRequests(),
            base.maxInFlightBytes(),
            base.maxLogBytes(),
            base.minFreeDiskBytes(),
            base.commitInterval(),
            base.refreshInterval(),
            base.retryRetention(),
            base.maxRpcDuration(),
            java.util.Optional.of(tls),
            List.of(principal("admin", "tls-token", Set.of(StandaloneConfig.Role.CATALOG))));
    try (var fixture = new Fixture(config)) {
      var denied = assertThrows(StatusRuntimeException.class, fixture::collection);
      assertThat(denied.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

      var authorized =
          CatalogServiceGrpc.newBlockingStub(fixture.authenticated("tls-token"))
              .withDeadlineAfter(10, TimeUnit.SECONDS);
      var created =
          authorized.createCollection(
              CreateCollectionRequest.newBuilder()
                  .setRequestId("tls-collection")
                  .setDatabase("db")
                  .setName("tls")
                  .build());

      assertThat(created.getCollectionId()).isNotEmpty();
    }
  }

  private static StandaloneConfig.Tls generateTls(Path directory) throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var keyPair = generator.generateKeyPair();
    var name = new X500Name("CN=localhost");
    var now = Instant.now();
    var certificate =
        new JcaX509v3CertificateBuilder(
                name,
                BigInteger.ONE,
                Date.from(now.minusSeconds(60)),
                Date.from(now.plus(Duration.ofDays(1))),
                name,
                keyPair.getPublic())
            .addExtension(
                Extension.subjectAlternativeName,
                false,
                new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")))
            .build(new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate()));
    Path certificateFile = directory.resolve("server-cert.pem");
    Path keyFile = directory.resolve("server-key.pem");
    writePem(certificateFile, new PemObject("CERTIFICATE", certificate.getEncoded()));
    writePem(keyFile, new PemObject("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
    return new StandaloneConfig.Tls(certificateFile, keyFile);
  }

  private static void writePem(Path path, PemObject object) throws Exception {
    try (var writer = new PemWriter(Files.newBufferedWriter(path))) {
      writer.writeObject(object);
    }
  }

  @Test
  public void write_serverDeadline_preservesAcceptedSourceForLaterResultRecovery()
      throws Exception {
    var base = StandaloneConfig.development(this.folder.getRoot().toPath(), 0);
    var config =
        new StandaloneConfig(
            base.dataPath(),
            base.address(),
            base.documentLimits(),
            base.maxInFlightRequests(),
            base.maxInFlightBytes(),
            base.maxLogBytes(),
            base.minFreeDiskBytes(),
            base.commitInterval(),
            base.refreshInterval(),
            base.retryRetention(),
            Duration.ofSeconds(2),
            base.tls(),
            base.principals());
    var blocker = new ApplyBlocker();
    try (var fixture = new Fixture(config, blocker)) {
      String collection = fixture.collection();
      fixture.index(collection);
      var request =
          WriteRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("deadline")
              .setMutation(
                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'deadline', title: 'term'}"))
              .build();
      var future = DocumentServiceGrpc.newFutureStub(fixture.channel).write(request);
      blocker.awaitEntered();

      var error =
          assertThrows(
              java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
      assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
      assertThat(
              fixture
                  .catalog()
                  .getCollection(
                      GetCollectionRequest.newBuilder().setCollectionId(collection).build())
                  .getAcceptedSequence())
          .isEqualTo(1);

      blocker.release();
      assertThat(fixture.documents().write(request).getResults(0).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXED);
    } finally {
      blocker.release();
    }
  }

  @Test
  public void writeBatch_mixedOutcomesAndRetryAfterDelete_preservesOriginalOutcomesAcrossRestart()
      throws Exception {
    String collection;
    String indexId;
    WriteBatchRequest batch;
    WriteResponse original;
    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0))) {
      collection = fixture.collection();
      var index = fixture.index(collection);
      indexId = index.getIndexId();
      batch =
          WriteBatchRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("batch")
              .addMutations(
                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a', title: 'first'}"))
              .addMutations(
                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a', title: 'duplicate'}"))
              .addMutations(
                  mutation(MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a', title: 'replacement'}"))
              .addMutations(mutation(MutationType.MUTATION_TYPE_DELETE, "{_id: 'b'}"))
              .addMutations(
                  Mutation.newBuilder()
                      .setType(MutationType.MUTATION_TYPE_UPSERT)
                      .setBson(ByteString.copyFrom(new byte[] {1, 2, 3})))
              .addMutations(
                  mutation(MutationType.MUTATION_TYPE_UPSERT, "{_id: 'b', title: 'other'}"))
              .build();

      original = fixture.documents().writeBatch(batch);

      assertThat(original.getComplete()).isTrue();
      assertThat(original.getResults(0).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXED);
      assertThat(original.getResults(1).getStatusCode())
          .isEqualTo(Status.Code.ALREADY_EXISTS.value());
      assertThat(original.getResults(2).getAcceptedSequence()).isEqualTo(2);
      assertThat(original.getResults(4).getStatusCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT.value());
      assertThat(original.getResults(5).getAcceptedSequence()).isEqualTo(4);
      fixture.visible(index, 4);
      fixture
          .documents()
          .write(
              WriteRequest.newBuilder()
                  .setRequestId("delete")
                  .setCollectionId(collection)
                  .setMutation(mutation(MutationType.MUTATION_TYPE_DELETE, "{_id: 'a'}"))
                  .build());
    }

    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0))) {
      var restored = fixture.ready(indexId);

      assertThat(restored.getCollectionId()).isEqualTo(collection);
      assertThat(fixture.documents().writeBatch(batch)).isEqualTo(original);
      assertThat(
              fixture
                  .catalog()
                  .getCollection(
                      GetCollectionRequest.newBuilder().setCollectionId(collection).build())
                  .getAcceptedSequence())
          .isEqualTo(5);
    }
  }

  @Test
  public void updateAndDrop_durableCatalogOperations_restoreNewRevisionAndRetainDrop()
      throws Exception {
    String collection;
    String indexId;
    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0))) {
      collection = fixture.collection();
      var index = fixture.index(collection);
      indexId = index.getIndexId();
      fixture
          .documents()
          .write(
              WriteRequest.newBuilder()
                  .setRequestId("write")
                  .setCollectionId(collection)
                  .setMutation(
                      mutation(
                          MutationType.MUTATION_TYPE_INSERT,
                          "{_id: 'a', title: 'historical', category: 'test'}"))
                  .build());

      fixture
          .catalog()
          .updateIndex(
              UpdateIndexRequest.newBuilder()
                  .setRequestId("update")
                  .setIndexId(indexId)
                  .setExpectedRevision(1)
                  .setDefinitionBson(bson("{mappings: {dynamic: true}, storedSource: true}"))
                  .build());
      var updated = fixture.readyRevision(indexId, 2);

      assertThat(updated.getActiveRevision()).isEqualTo(2);
      var historical =
          fixture.search(
              indexId, "{text: {path: 'title', query: 'historical'}, returnStoredSource: true}");
      assertThat(results(historical)).hasSize(1);
      assertThat(results(historical).get(0).asDocument().getDocument("storedSource"))
          .isEqualTo(BsonDocument.parse("{_id: 'a', title: 'historical', category: 'test'}"));
      fixture
          .catalog()
          .dropIndex(
              DropIndexRequest.newBuilder()
                  .setRequestId("drop")
                  .setIndexId(indexId)
                  .setExpectedRevision(2)
                  .build());
    }

    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0))) {
      var dropped =
          fixture.catalog().getIndex(GetIndexRequest.newBuilder().setIndexId(indexId).build());

      assertThat(dropped.getLifecycleState()).isEqualTo("DROPPED");
      assertThat(
              fixture
                  .catalog()
                  .listIndexes(ListIndexesRequest.newBuilder().setCollectionId(collection).build())
                  .getIndexesList())
          .isEmpty();
      assertThat(
              fixture
                  .catalog()
                  .getCollection(
                      GetCollectionRequest.newBuilder().setCollectionId(collection).build())
                  .getAcceptedSequence())
          .isEqualTo(1);
    }
  }

  @Test
  public void authentication_rolesAndRetryNamespaces_enforcesPrincipalIsolation() throws Exception {
    var base = StandaloneConfig.development(this.folder.getRoot().toPath(), 0);
    var config =
        new StandaloneConfig(
            base.dataPath(),
            base.address(),
            base.documentLimits(),
            base.maxInFlightRequests(),
            base.maxInFlightBytes(),
            base.maxLogBytes(),
            base.minFreeDiskBytes(),
            base.commitInterval(),
            base.refreshInterval(),
            base.retryRetention(),
            base.maxRpcDuration(),
            base.tls(),
            List.of(
                principal("admin", "admin-token", Set.of(StandaloneConfig.Role.CATALOG)),
                principal("alice", "alice-token", Set.of(StandaloneConfig.Role.WRITE)),
                principal("bob", "bob-token", Set.of(StandaloneConfig.Role.WRITE))));
    try (var fixture = new Fixture(config)) {
      var unauthenticated = assertThrows(StatusRuntimeException.class, fixture::collection);
      assertThat(unauthenticated.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

      var admin = fixture.authenticated("admin-token");
      var adminCatalog =
          CatalogServiceGrpc.newBlockingStub(admin).withDeadlineAfter(10, TimeUnit.SECONDS);
      String collection =
          adminCatalog
              .createCollection(
                  CreateCollectionRequest.newBuilder()
                      .setRequestId("collection")
                      .setDatabase("database")
                      .setName("collection")
                      .build())
              .getCollectionId();
      var index = adminCatalog.createIndex(indexRequest(collection));
      fixture.ready(adminCatalog, index.getIndexId(), 1);
      var alice = fixture.authenticated("alice-token");
      var denied =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  CatalogServiceGrpc.newBlockingStub(alice)
                      .createCollection(
                          CreateCollectionRequest.newBuilder()
                              .setRequestId("denied")
                              .setDatabase("database")
                              .setName("denied")
                              .build()));
      assertThat(denied.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);

      DocumentServiceGrpc.newBlockingStub(alice)
          .withDeadlineAfter(10, TimeUnit.SECONDS)
          .write(
              WriteRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("private")
                  .setMutation(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}"))
                  .build());
      var hidden =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  DocumentServiceGrpc.newBlockingStub(fixture.authenticated("bob-token"))
                      .getWriteResult(
                          GetWriteResultRequest.newBuilder()
                              .setCollectionId(collection)
                              .setRequestId("private")
                              .build()));

      assertThat(hidden.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }
  }

  @Test
  public void query_textPaginationAndHistoricalVectorIndex_runsEntirelyThroughGrpc()
      throws Exception {
    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0))) {
      String collection = fixture.collection();
      var text = fixture.index(collection);
      fixture
          .documents()
          .writeBatch(
              WriteBatchRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("write")
                  .addMutations(
                      mutation(
                          MutationType.MUTATION_TYPE_INSERT,
                          "{_id: 'a', title: 'needle', embedding: [1.0, 0.0, 0.0]}"))
                  .addMutations(
                      mutation(
                          MutationType.MUTATION_TYPE_INSERT,
                          "{_id: 'b', title: 'needle', embedding: [0.0, 1.0, 0.0]}"))
                  .build());
      fixture.visible(text, 2);

      var first =
          fixture
              .queries()
              .search(
                  QueryRequest.newBuilder()
                      .setIndexId(text.getIndexId())
                      .setBatchSize(1)
                      .setQueryBson(bson("{text: {path: 'title', query: 'needle'}}"))
                      .build());
      var next =
          fixture
              .queries()
              .getMore(
                  GetMoreRequest.newBuilder()
                      .setCursorId(first.getCursorId())
                      .setBatchSize(1)
                      .build());

      assertThat(first.getCursorId()).isGreaterThan(0);
      assertThat(results(first)).hasSize(1);
      assertThat(results(next)).hasSize(1);
      assertThat(results(first).get(0).asDocument().getString("_id"))
          .isNotEqualTo(results(next).get(0).asDocument().getString("_id"));

      var vector =
          fixture
              .catalog()
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setCollectionId(collection)
                      .setRequestId("vector")
                      .setName("vector")
                      .setType(IndexType.INDEX_TYPE_VECTOR_SEARCH)
                      .setDefinitionBson(
                          bson(
                              "{fields: [{type: 'vector', path: 'embedding', numDimensions: 3,"
                                  + " similarity: 'cosine'}]}"))
                      .build());
      var ready = fixture.ready(vector.getIndexId());
      fixture.visible(ready, 2);
      var nearest =
          fixture
              .queries()
              .vectorSearch(
                  QueryRequest.newBuilder()
                      .setIndexId(vector.getIndexId())
                      .setQueryBson(
                          bson(
                              "{path: 'embedding', queryVector: [1.0, 0.0, 0.0], exact: true,"
                                  + " limit: 1}"))
                      .build());

      assertThat(results(nearest)).hasSize(1);
      assertThat(results(nearest).get(0).asDocument().getString("_id").getValue()).isEqualTo("a");
    }
  }

  @Test
  public void killCursor_subsequentGetMore_returnsNotFound() throws Exception {
    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0))) {
      String collection = fixture.collection();
      var index = fixture.index(collection);
      fixture
          .documents()
          .writeBatch(
              WriteBatchRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("write")
                  .addMutations(
                      mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a', title: 'needle'}"))
                  .addMutations(
                      mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'b', title: 'needle'}"))
                  .build());
      fixture.visible(index, 2);
      var cursor =
          fixture
              .queries()
              .search(
                  QueryRequest.newBuilder()
                      .setIndexId(index.getIndexId())
                      .setBatchSize(1)
                      .setQueryBson(bson("{text: {path: 'title', query: 'needle'}}"))
                      .build());

      fixture
          .queries()
          .killCursor(KillCursorRequest.newBuilder().setCursorId(cursor.getCursorId()).build());
      var error =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  fixture
                      .queries()
                      .getMore(
                          GetMoreRequest.newBuilder().setCursorId(cursor.getCursorId()).build()));

      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }
  }

  @Test
  public void dropIndex_openCursor_subsequentGetMoreReturnsNotFound() throws Exception {
    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0))) {
      String collection = fixture.collection();
      var index = fixture.index(collection);
      fixture
          .documents()
          .writeBatch(
              WriteBatchRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("write")
                  .addMutations(
                      mutation(MutationType.MUTATION_TYPE_INSERT, "{_id:'a',title:'needle'}"))
                  .addMutations(
                      mutation(MutationType.MUTATION_TYPE_INSERT, "{_id:'b',title:'needle'}"))
                  .build());
      fixture.visible(index, 2);
      var cursor =
          fixture
              .queries()
              .search(
                  QueryRequest.newBuilder()
                      .setIndexId(index.getIndexId())
                      .setBatchSize(1)
                      .setQueryBson(bson("{text:{path:'title',query:'needle'}}"))
                      .build());
      assertThat(cursor.getCursorId()).isGreaterThan(0);

      fixture
          .catalog()
          .dropIndex(
              DropIndexRequest.newBuilder()
                  .setIndexId(index.getIndexId())
                  .setRequestId("drop")
                  .setExpectedRevision(1)
                  .build());
      var error =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  fixture
                      .queries()
                      .getMore(
                          GetMoreRequest.newBuilder().setCursorId(cursor.getCursorId()).build()));

      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }
  }

  private static org.bson.BsonArray results(QueryResponse response) {
    return new org.bson.RawBsonDocument(response.getBatchBson().toByteArray()).getArray("results");
  }

  @Test
  public void write_pausedTarget_keepsSingleRpcPendingUntilIndexed() throws Exception {
    exerciseBlockingWrite(false);
  }

  @Test
  public void writeBatch_pausedTarget_keepsBatchRpcPendingUntilIndexed() throws Exception {
    exerciseBlockingWrite(true);
  }

  @Test
  public void write_cancelledAfterAcceptance_recoversThroughGetWriteResultWithoutReapplying()
      throws Exception {
    var blocker = new ApplyBlocker();
    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0), blocker)) {
      String collection = fixture.collection();
      fixture.index(collection);
      var request =
          WriteRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("cancelled")
              .setMutation(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}"))
              .build();
      var pending =
          DocumentServiceGrpc.newFutureStub(fixture.channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .write(request);
      blocker.awaitEntered();

      pending.cancel(true);
      blocker.release();
      var recovered = fixture.writeResult(collection, "cancelled", true);
      var retried = fixture.documents().write(request);

      assertThat(recovered.getResults(0).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXED);
      assertThat(retried).isEqualTo(recovered);
      assertThat(
              fixture
                  .catalog()
                  .getCollection(
                      GetCollectionRequest.newBuilder().setCollectionId(collection).build())
                  .getAcceptedSequence())
          .isEqualTo(1);
    } finally {
      blocker.release();
    }
  }

  @Test
  public void write_admissionFull_rejectsBeforeAcceptingAnotherSourceMutation() throws Exception {
    var base = StandaloneConfig.development(this.folder.getRoot().toPath(), 0);
    var config =
        new StandaloneConfig(
            base.dataPath(),
            base.address(),
            base.documentLimits(),
            1,
            base.maxInFlightBytes(),
            base.maxLogBytes(),
            base.minFreeDiskBytes(),
            base.commitInterval(),
            base.refreshInterval(),
            base.retryRetention(),
            base.maxRpcDuration(),
            base.tls(),
            base.principals());
    var blocker = new ApplyBlocker();
    try (var fixture = new Fixture(config, blocker)) {
      String collection = fixture.collection();
      fixture.index(collection);
      var pending =
          DocumentServiceGrpc.newFutureStub(fixture.channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .write(
                  WriteRequest.newBuilder()
                      .setCollectionId(collection)
                      .setRequestId("first")
                      .setMutation(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}"))
                      .build());
      blocker.awaitEntered();

      var rejected =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  fixture
                      .documents()
                      .write(
                          WriteRequest.newBuilder()
                              .setCollectionId(collection)
                              .setRequestId("second")
                              .setMutation(
                                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'b'}"))
                              .build()));

      assertThat(rejected.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(fixture.runtime.catalog().getCollection(collection).getAcceptedSequence())
          .isEqualTo(1);
      blocker.release();
      assertThat(pending.get(10, TimeUnit.SECONDS).getComplete()).isTrue();
    } finally {
      blocker.release();
    }
  }

  private void exerciseBlockingWrite(boolean batch) throws Exception {
    var blocker = new ApplyBlocker();
    try (var fixture =
        new Fixture(StandaloneConfig.development(this.folder.getRoot().toPath(), 0), blocker)) {
      String collection = fixture.collection();
      fixture.index(collection);
      var stub =
          DocumentServiceGrpc.newFutureStub(fixture.channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS);
      var pending =
          batch
              ? stub.writeBatch(
                  WriteBatchRequest.newBuilder()
                      .setCollectionId(collection)
                      .setRequestId("wait")
                      .addMutations(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}"))
                      .addMutations(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'b'}"))
                      .build())
              : stub.write(
                  WriteRequest.newBuilder()
                      .setCollectionId(collection)
                      .setRequestId("wait")
                      .setMutation(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}"))
                      .build());
      blocker.awaitEntered();
      var accepted = fixture.writeResult(collection, "wait", false);

      assertThat(pending.isDone()).isFalse();
      assertThat(accepted.getResults(0).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_PENDING_INDEXING);
      assertThat(accepted.getResults(0).hasAcceptedSequence()).isTrue();

      blocker.release();
      var completed = pending.get(10, TimeUnit.SECONDS);

      assertThat(completed.getComplete()).isTrue();
      assertThat(
              completed.getResultsList().stream()
                  .map(result -> result.getState())
                  .distinct()
                  .toList())
          .containsExactly(WriteItemState.WRITE_ITEM_STATE_INDEXED);
    } finally {
      blocker.release();
    }
  }

  private static final class ApplyBlocker implements java.util.function.LongConsumer {
    private final java.util.concurrent.CountDownLatch entered =
        new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch released =
        new java.util.concurrent.CountDownLatch(1);

    @Override
    public void accept(long sequence) {
      this.entered.countDown();
      try {
        if (!this.released.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Test apply barrier timed out");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    private void awaitEntered() throws Exception {
      if (!this.entered.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Target never reached apply boundary");
      }
    }

    private void release() {
      this.released.countDown();
    }
  }

  private static final class SnapshotBlocker implements Runnable {
    private final ApplyBlocker barrier = new ApplyBlocker();
    private volatile boolean armed;

    private void arm() {
      this.armed = true;
    }

    @Override
    public void run() {
      if (this.armed) {
        this.barrier.accept(0);
      }
    }

    private void awaitEntered() throws Exception {
      this.barrier.awaitEntered();
    }

    private void release() {
      this.barrier.release();
    }
  }

  private static StandaloneConfig.Principal principal(
      String name, String token, Set<StandaloneConfig.Role> roles) {
    return new StandaloneConfig.Principal(
        name,
        LocalAuthoritativeCatalog.digest(token.getBytes(StandardCharsets.US_ASCII)),
        roles,
        Set.of("*"));
  }

  private static CreateIndexRequest indexRequest(String collection) {
    return CreateIndexRequest.newBuilder()
        .setRequestId("create")
        .setCollectionId(collection)
        .setName("text")
        .setType(IndexType.INDEX_TYPE_SEARCH)
        .setDefinitionBson(bson("{mappings: {dynamic: true}}"))
        .build();
  }

  private static Mutation mutation(MutationType type, String json) {
    return Mutation.newBuilder().setType(type).setBson(bson(json)).build();
  }

  private static ByteString bson(String json) {
    return ByteString.copyFrom(StandaloneDefinitionValidator.encode(BsonDocument.parse(json)));
  }

  private static final class Fixture implements AutoCloseable {
    private final StandaloneRuntime runtime;
    private final StandaloneGrpcServer server;
    private final ManagedChannel channel;

    private Fixture(StandaloneConfig config) throws Exception {
      this(config, unused -> {});
    }

    private Fixture(StandaloneConfig config, java.util.function.LongConsumer beforeApply)
        throws Exception {
      this(config, beforeApply, () -> {});
    }

    private Fixture(
        StandaloneConfig config,
        java.util.function.LongConsumer beforeApply,
        Runnable beforeSnapshot)
        throws Exception {
      Similarity.load();
      this.runtime = new StandaloneRuntime(config, beforeApply, beforeSnapshot);
      this.server = new StandaloneGrpcServer(this.runtime).start();
      var builder =
          NettyChannelBuilder.forAddress(
              config.address().getAddress().getHostAddress(), this.server.port());
      if (config.tls().isPresent()) {
        builder.sslContext(
            GrpcSslContexts.forClient()
                .sslProvider(io.netty.handler.ssl.SslProvider.JDK)
                .trustManager(config.tls().get().certificate().toFile())
                .build());
      } else {
        builder.usePlaintext();
      }
      this.channel = builder.build();
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub catalog() {
      return CatalogServiceGrpc.newBlockingStub(this.channel)
          .withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private DocumentServiceGrpc.DocumentServiceBlockingStub documents() {
      return DocumentServiceGrpc.newBlockingStub(this.channel)
          .withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private QueryServiceGrpc.QueryServiceBlockingStub queries() {
      return QueryServiceGrpc.newBlockingStub(this.channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private QueryResponse search(String indexId, String expression) {
      return queries()
          .search(
              QueryRequest.newBuilder().setIndexId(indexId).setQueryBson(bson(expression)).build());
    }

    private WriteResponse writeResult(String collection, String requestId, boolean complete)
        throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < until) {
        var result =
            documents()
                .getWriteResult(
                    GetWriteResultRequest.newBuilder()
                        .setCollectionId(collection)
                        .setRequestId(requestId)
                        .build());
        if (result.getResultsCount() > 0 && result.getComplete() == complete) {
          return result;
        }
        Thread.sleep(10);
      }
      throw new AssertionError("Write result did not reach expected completion state");
    }

    private WriteResponse acceptedItems(String collection, String requestId, int count)
        throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < until) {
        var response =
            documents()
                .getWriteResult(
                    GetWriteResultRequest.newBuilder()
                        .setCollectionId(collection)
                        .setRequestId(requestId)
                        .build());
        if (response.getResultsCount() == count) {
          return response;
        }
        Thread.sleep(10);
      }
      throw new AssertionError("Batch did not finish source acceptance");
    }

    private IndexDescription indexFailure(String indexId) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < until) {
        var index = catalog().getIndex(GetIndexRequest.newBuilder().setIndexId(indexId).build());
        if (!index.getFailureMessage().isEmpty()) {
          return index;
        }
        Thread.sleep(10);
      }
      throw new AssertionError("Injected build failure was not reported");
    }

    private void managementFailure() throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < until && this.runtime.managementReady()) {
        Thread.sleep(10);
      }
      assertThat(this.runtime.managementReady()).isFalse();
    }

    private String collection() {
      return catalog()
          .createCollection(
              CreateCollectionRequest.newBuilder()
                  .setRequestId("collection")
                  .setDatabase("database")
                  .setName("collection")
                  .build())
          .getCollectionId();
    }

    private IndexDescription index(String collection) throws Exception {
      return ready(catalog().createIndex(indexRequest(collection)).getIndexId());
    }

    private IndexDescription ready(String id) throws Exception {
      return readyRevision(id, 1);
    }

    private IndexDescription ready(
        CatalogServiceGrpc.CatalogServiceBlockingStub stub, String id, long revision)
        throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        var status =
            stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                .getIndex(GetIndexRequest.newBuilder().setIndexId(id).build());
        if (status.getLifecycleState().equals("READY") && status.getActiveRevision() == revision) {
          return status;
        }
        Thread.sleep(10);
      }
      throw new AssertionError(
          "Index did not reach READY: "
              + stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                  .getIndex(GetIndexRequest.newBuilder().setIndexId(id).build()));
    }

    private IndexDescription readyRevision(String id, long revision) throws Exception {
      return ready(catalog(), id, revision);
    }

    private void visible(IndexDescription index, long sequence) {
      documents()
          .awaitSequence(
              AwaitSequenceRequest.newBuilder()
                  .setIndexId(index.getIndexId())
                  .setGenerationId(index.getLive().getGenerationId())
                  .setSequence(sequence)
                  .setCondition(SequenceCondition.SEQUENCE_CONDITION_VISIBLE)
                  .build());
    }

    private Channel authenticated(String token) {
      var headers = new Metadata();
      headers.put(
          Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
      return io.grpc.ClientInterceptors.intercept(
          this.channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Override
    public void close() throws Exception {
      this.channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      this.server.close();
      this.runtime.close();
    }
  }
}
