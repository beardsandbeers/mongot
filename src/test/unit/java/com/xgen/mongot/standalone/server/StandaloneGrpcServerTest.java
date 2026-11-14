package com.xgen.mongot.standalone.server;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.grpc.CatalogServiceGrpc;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DeleteRequest;
import com.xgen.mongot.standalone.grpc.DocumentServiceGrpc;
import com.xgen.mongot.standalone.grpc.DocumentWriteConcern;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.GetIndexRequest;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.ListIndexesRequest;
import com.xgen.mongot.standalone.grpc.QueryRequest;
import com.xgen.mongot.standalone.grpc.QueryServiceGrpc;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.grpc.UpsertRequest;
import com.xgen.mongot.standalone.runtime.StandaloneRuntime;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StandaloneGrpcServerTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void push_upsertsAndDeletesOnlyTheSelectedIndex() throws Exception {
    try (var f = new Fixture(data())) {
      String first = f.create("first");
      String second = f.create("second");
      f.upsert(first, "{_id: 'a', title: 'coffee', privateField: 'not-stored'}");
      assertThat(f.results(first, "coffee").getArray("results")).hasSize(1);
      assertThat(f.results(second, "coffee").getArray("results")).isEmpty();
      f.upsert(first, "{_id: 'a', title: 'tea'}");
      assertThat(f.results(first, "coffee").getArray("results")).isEmpty();
      assertThat(f.results(first, "tea").getArray("results")).hasSize(1);
      var deleted =
          f.docs.delete(
              DeleteRequest.newBuilder()
                  .setIndexId(first)
                  .addIdsBson(bson("{_id: 'a'}"))
                  .addIdsBson(bson("{_id: 'missing'}"))
                  .build());
      assertThat(deleted.getProcessedCount()).isEqualTo(2);
      assertThat(f.results(first, "tea").getArray("results")).isEmpty();
    }
  }

  @Test
  public void restart_preservesCommittedLuceneDataAndIndexDefinitionsOnly() throws Exception {
    Path data = data();
    String id;
    try (var f = new Fixture(data)) {
      id = f.create("persistent");
      f.docs.upsert(
          UpsertRequest.newBuilder()
              .setIndexId(id)
              .setWriteConcern(DocumentWriteConcern.DOCUMENT_WRITE_CONCERN_COMMITTED)
              .addDocumentsBson(
                  bson("{_id: 'a', title: 'coffee', privateField: 'secret-content-marker'}"))
              .build());
    }
    try (var f = new Fixture(data)) {
      f.ready(id, 1);
      var result = f.results(id, "coffee").getArray("results");
      assertThat(result).hasSize(1);
      assertThat(result.get(0).asDocument().containsKey("storedSource")).isFalse();
      assertThat(f.catalog.listIndexes(ListIndexesRequest.getDefaultInstance()).getIndexesCount())
          .isEqualTo(1);
    }
    assertThat(Files.exists(data.resolve("standalone.sqlite"))).isFalse();
    assertThat(Files.readString(data.resolve("indexes.json")))
        .doesNotContain("secret-content-marker");
    try (var paths = Files.list(data)) {
      assertThat(paths.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList())
          .containsExactly(
              "standalone.lock", "indexes.json", "configJournal.json", "dynamicFeatureFlags.json");
    }
  }

  @Test
  public void mappingUpdate_startsEmptyAndRequiresReingestion() throws Exception {
    try (var f = new Fixture(data())) {
      String id = f.create("schema");
      f.upsert(id, "{_id: 'a', title: 'coffee'}");
      String oldGeneration = f.ready(id, 1).getGenerationId();
      f.catalog.updateIndex(
          UpdateIndexRequest.newBuilder()
              .setIndexId(id)
              .setExpectedRevision(1)
              .setDefinitionBson(
                  bson("{mappings: {dynamic: false, fields: {title: {type: 'string'}}}}"))
              .build());
      var updated = f.ready(id, 2);
      assertThat(updated.getGenerationId()).isNotEqualTo(oldGeneration);
      assertThat(f.results(id, "coffee").getArray("results")).isEmpty();
      var stale =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  f.docs.upsert(
                      UpsertRequest.newBuilder()
                          .setIndexId(id)
                          .setGenerationId(oldGeneration)
                          .addDocumentsBson(bson("{_id: 'a', title: 'coffee'}"))
                          .build()));
      assertThat(stale.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
      f.upsert(id, "{_id: 'a', title: 'coffee'}");
      assertThat(f.results(id, "coffee").getArray("results")).hasSize(1);
    }
  }

  @Test
  public void newIndex_neverBackfillsContentFromAnotherIndex() throws Exception {
    try (var f = new Fixture(data())) {
      String first = f.create("first");
      f.upsert(first, "{_id: 'a', title: 'coffee'}");
      String second = f.create("second");
      assertThat(f.results(second, "coffee").getArray("results")).isEmpty();
      f.catalog.dropIndex(
          DropIndexRequest.newBuilder().setIndexId(first).setExpectedRevision(1).build());
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.docs.upsert(
                              UpsertRequest.newBuilder()
                                  .setIndexId(first)
                                  .addDocumentsBson(bson("{_id: 'a'}"))
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.NOT_FOUND);
      String replacement = f.create("first");
      assertThat(replacement).isNotEqualTo(first);
      assertThat(f.results(replacement, "coffee").getArray("results")).isEmpty();
    }
  }

  @Test
  public void validation_rejectsMalformedBatchBeforeWriting() throws Exception {
    try (var f = new Fixture(data())) {
      String id = f.create("validation");
      var error =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  f.docs.upsert(
                      UpsertRequest.newBuilder()
                          .setIndexId(id)
                          .addDocumentsBson(bson("{_id:'a',title:'coffee'}"))
                          .addDocumentsBson(bson("{title:'missing id'}"))
                          .build()));
      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(f.results(id, "coffee").getArray("results")).isEmpty();
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.docs.delete(
                              DeleteRequest.newBuilder()
                                  .setIndexId(id)
                                  .addIdsBson(bson("{_id:'a',other:1}"))
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  public void vectorPush_usesExistingVectorSchemaAndReader() throws Exception {
    com.xgen.mongot.index.lucene.vector.Similarity.load();
    try (var f = new Fixture(data())) {
      var index =
          f.catalog.createIndex(
              CreateIndexRequest.newBuilder()
                  .setName("vectors")
                  .setType(IndexType.INDEX_TYPE_VECTOR_SEARCH)
                  .setDefinitionBson(
                      bson(
                          "{fields:[{type:'vector',path:'embedding',"
                              + "numDimensions:3,similarity:'cosine'}]}"))
                  .build());
      String id = index.getIndexId();
      f.ready(id, 1);
      f.upsert(id, "{_id:'near',embedding:[1.0,0.0,0.0]}");
      var result =
          f.queries.vectorSearch(
              QueryRequest.newBuilder()
                  .setIndexId(id)
                  .setQueryBson(
                      bson("{path:'embedding',queryVector:[1.0,0.0,0.0],exact:true,limit:1}"))
                  .build());
      assertThat(new RawBsonDocument(result.getBatchBson().toByteArray()).getArray("results"))
          .hasSize(1);
    }
  }

  @Test
  public void ownershipLock_rejectsSecondRuntime() throws Exception {
    Path data = data();
    try (var f = new Fixture(data)) {
      assertThrows(
          java.io.IOException.class,
          () -> new StandaloneRuntime(StandaloneConfig.development(data, 0)));
    }
  }

  private Path data() {
    return this.folder.getRoot().toPath().resolve("data");
  }

  private static ByteString bson(String json) {
    return ByteString.copyFrom(StandaloneDefinitionValidator.encode(BsonDocument.parse(json)));
  }

  private static final class Fixture implements AutoCloseable {
    private final StandaloneRuntime runtime;
    private final StandaloneGrpcServer server;
    private final ManagedChannel channel;
    private final CatalogServiceGrpc.CatalogServiceBlockingStub catalog;
    private final DocumentServiceGrpc.DocumentServiceBlockingStub docs;
    private final QueryServiceGrpc.QueryServiceBlockingStub queries;

    private Fixture(Path data) throws Exception {
      this.runtime = new StandaloneRuntime(StandaloneConfig.development(data, 0));
      this.server = new StandaloneGrpcServer(this.runtime).start();
      this.channel =
          NettyChannelBuilder.forAddress("127.0.0.1", this.server.port()).usePlaintext().build();
      this.catalog =
          CatalogServiceGrpc.newBlockingStub(this.channel).withDeadlineAfter(45, TimeUnit.SECONDS);
      this.docs =
          DocumentServiceGrpc.newBlockingStub(this.channel).withDeadlineAfter(45, TimeUnit.SECONDS);
      this.queries =
          QueryServiceGrpc.newBlockingStub(this.channel).withDeadlineAfter(45, TimeUnit.SECONDS);
    }

    private String create(String name) throws Exception {
      String id =
          this.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName(name)
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(bson("{mappings:{dynamic:true}}"))
                      .build())
              .getIndexId();
      ready(id, 1);
      return id;
    }

    private IndexDescription ready(String id, long revision) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        var index = this.catalog.getIndex(GetIndexRequest.newBuilder().setIndexId(id).build());
        if (index.getLifecycleState().equals("READY") && index.getActiveRevision() == revision) {
          return index;
        }
        Thread.sleep(20);
      }
      throw new AssertionError(
          "Index never became ready: "
              + this.catalog.getIndex(GetIndexRequest.newBuilder().setIndexId(id).build()));
    }

    private void upsert(String id, String json) {
      assertThat(
              this.docs
                  .upsert(
                      UpsertRequest.newBuilder()
                          .setIndexId(id)
                          .addDocumentsBson(bson(json))
                          .build())
                  .getIndexedCount())
          .isEqualTo(1);
    }

    private BsonDocument results(String id, String term) {
      return new RawBsonDocument(
          this.queries
              .search(
                  QueryRequest.newBuilder()
                      .setIndexId(id)
                      .setQueryBson(bson("{text:{path:'title',query:'" + term + "'}}"))
                      .build())
              .getBatchBson()
              .toByteArray());
    }

    @Override
    public void close() throws Exception {
      this.channel.shutdownNow();
      this.channel.awaitTermination(5, TimeUnit.SECONDS);
      try {
        this.server.close();
      } finally {
        this.runtime.close();
      }
    }
  }
}
