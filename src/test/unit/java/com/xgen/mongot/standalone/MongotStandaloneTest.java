package com.xgen.mongot.standalone;

import static com.google.common.truth.Truth.assertThat;

import com.google.protobuf.ByteString;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.grpc.CatalogServiceGrpc;
import com.xgen.mongot.standalone.grpc.CreateCollectionRequest;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DocumentServiceGrpc;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.GetCollectionRequest;
import com.xgen.mongot.standalone.grpc.GetIndexRequest;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.Mutation;
import com.xgen.mongot.standalone.grpc.MutationType;
import com.xgen.mongot.standalone.grpc.QueryRequest;
import com.xgen.mongot.standalone.grpc.QueryServiceGrpc;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.grpc.WriteItemState;
import com.xgen.mongot.standalone.grpc.WriteRequest;
import com.xgen.mongot.standalone.grpc.WriteResponse;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real executable acceptance: source durability must survive a kill before Lucene commit. */
public class MongotStandaloneTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void process_killBeforeProjectionCommit_recoversAcknowledgedDocumentsAndRetryIdentity()
      throws Exception {
    Path data = this.folder.getRoot().toPath().resolve("data");
    String collection;
    String indexId;
    String droppedIndex;
    WriteRequest request;
    WriteResponse acknowledged;
    try (var process = new ProcessFixture(this.folder.getRoot().toPath(), data)) {
      collection = process.collection();
      var index =
          process
              .catalog()
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setCollectionId(collection)
                      .setRequestId("index")
                      .setName("text")
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(bson("{mappings: {dynamic: true}, storedSource: true}"))
                      .build());
      indexId = index.getIndexId();
      process.ready(indexId, 0);
      request =
          WriteRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("write")
              .setMutation(
                  Mutation.newBuilder()
                      .setType(MutationType.MUTATION_TYPE_INSERT)
                      .setBson(
                          bson("{_id: 'a', title: 'durableterm', embedding: [1.0, 0.0, 0.0]}")))
              .build();

      acknowledged = process.documents().write(request);

      assertThat(acknowledged.getResults(0).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXED);
      assertThat(
              process
                  .catalog()
                  .getIndex(GetIndexRequest.newBuilder().setIndexId(indexId).build())
                  .getLive()
                  .getCommittedSequence())
          .isEqualTo(0);
      process.kill();
    }

    try (var process = new ProcessFixture(this.folder.getRoot().toPath(), data)) {
      process.ready(indexId, 1);
      var found = process.query(indexId);

      assertThat(found.getArray("results")).hasSize(1);
      assertThat(found.getArray("results").get(0).asDocument().getDocument("storedSource"))
          .isEqualTo(
              BsonDocument.parse("{_id: 'a', title: 'durableterm', embedding: [1.0, 0.0, 0.0]}"));
      assertThat(process.documents().write(request)).isEqualTo(acknowledged);
      assertThat(
              process
                  .catalog()
                  .getCollection(
                      GetCollectionRequest.newBuilder().setCollectionId(collection).build())
                  .getAcceptedSequence())
          .isEqualTo(1);
      var vector =
          process
              .catalog()
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setCollectionId(collection)
                      .setRequestId("vector-index")
                      .setName("vector")
                      .setType(IndexType.INDEX_TYPE_VECTOR_SEARCH)
                      .setDefinitionBson(
                          bson(
                              "{fields: [{type: 'vector', path: 'embedding', numDimensions: 3,"
                                  + " similarity: 'cosine'}]}"))
                      .build());
      process.ready(vector.getIndexId(), 1);
      droppedIndex = vector.getIndexId();
      assertThat(
              process
                  .vector(vector.getIndexId())
                  .getArray("results")
                  .get(0)
                  .asDocument()
                  .getString("_id")
                  .getValue())
          .isEqualTo("a");
      process
          .documents()
          .write(
              WriteRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("replace-b")
                  .setMutation(
                      Mutation.newBuilder()
                          .setType(MutationType.MUTATION_TYPE_UPSERT)
                          .setBson(bson("{_id:'b',title:'temporary'}")))
                  .build());
      process
          .documents()
          .write(
              WriteRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("delete-b")
                  .setMutation(
                      Mutation.newBuilder()
                          .setType(MutationType.MUTATION_TYPE_DELETE)
                          .setBson(bson("{_id:'b'}")))
                  .build());
      process
          .catalog()
          .updateIndex(
              UpdateIndexRequest.newBuilder()
                  .setIndexId(indexId)
                  .setRequestId("mapping")
                  .setExpectedRevision(1)
                  .setDefinitionBson(
                      bson(
                          "{mappings:{dynamic:false,fields:{title:{type:'string'},"
                              + "newField:{type:'string'}}},storedSource:true}"))
                  .build());
      process
          .documents()
          .write(
              WriteRequest.newBuilder()
                  .setCollectionId(collection)
                  .setRequestId("during-rebuild")
                  .setMutation(
                      Mutation.newBuilder()
                          .setType(MutationType.MUTATION_TYPE_UPSERT)
                          .setBson(
                              bson(
                                  "{_id:'a',title:'durableterm',newField:'local',"
                                      + "embedding:[1.0,0.0,0.0]}")))
                  .build());
      process.ready(indexId, 4);
      assertThat(process.query(indexId).getArray("results")).hasSize(1);
      process
          .catalog()
          .dropIndex(
              DropIndexRequest.newBuilder()
                  .setIndexId(droppedIndex)
                  .setRequestId("drop-vector")
                  .setExpectedRevision(1)
                  .build());
    }

    Path backup = this.folder.getRoot().toPath().resolve("restored");
    copyTree(data, backup);
    deleteProjectionDirectories(backup);
    try (var restored = new ProcessFixture(this.folder.getRoot().toPath(), backup)) {
      restored.ready(indexId, 4);

      assertThat(restored.query(indexId).getArray("results")).hasSize(1);
      assertThat(restored.documents().write(request)).isEqualTo(acknowledged);
      assertThat(restored.collection()).isEqualTo(collection);
      assertThat(
              restored
                  .catalog()
                  .getIndex(GetIndexRequest.newBuilder().setIndexId(droppedIndex).build())
                  .getLifecycleState())
          .isEqualTo("DROPPED");
    }
  }

  private static ByteString bson(String json) {
    return ByteString.copyFrom(StandaloneDefinitionValidator.encode(BsonDocument.parse(json)));
  }

  private static void copyTree(Path source, Path target) throws Exception {
    try (var paths = Files.walk(source)) {
      for (Path path : paths.toList()) {
        Path destination = target.resolve(source.relativize(path));
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.copy(path, destination);
        }
      }
    }
  }

  private static void deleteProjectionDirectories(Path root) throws Exception {
    // Standalone authoritative SQLite state and physical journal are root files. All child
    // directories here are Lucene projections/metadata or the factory's trash directory.
    try (var children = Files.list(root)) {
      for (Path child : children.filter(Files::isDirectory).toList()) {
        try (var paths = Files.walk(child)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(path);
          }
        }
      }
    }
  }

  private static final class ProcessFixture implements AutoCloseable {
    private final Process process;
    private final ManagedChannel channel;
    private final Path log;
    private final Path network;

    private ProcessFixture(Path work, Path data) throws Exception {
      int port;
      try (var socket = new ServerSocket(0)) {
        port = socket.getLocalPort();
      }
      this.log = Files.createTempFile(work, "standalone-", ".log");
      this.network = Files.createTempFile(work, "standalone-network-", ".log");
      Path config = Files.createTempFile(work, "standalone-", ".yaml");
      Files.writeString(
          config,
          "mode: standalone\nstorage:\n  dataPath: '"
              + data.toAbsolutePath()
              + "'\n"
              + "server:\n  grpc:\n    address: '127.0.0.1:"
              + port
              + "'\n"
              + "ingestion:\n"
              + "  commitIntervalMillis: 3600000\n"
              + "querying:\n"
              + "  refreshIntervalMillis: 25\n");
      var command = new ArrayList<String>();
      if ("1".equals(System.getenv("MONGOT_STANDALONE_NETWORK_GUARD"))) {
        command.addAll(
            java.util.List.of(
                "strace",
                "-f",
                "-e",
                "trace=network",
                "-e",
                "inject=connect:error=EACCES",
                "-o",
                this.network.toString()));
      }
      String launcher = System.getenv("MONGOT_STANDALONE_LAUNCHER");
      if (launcher != null && !launcher.isBlank()) {
        command.addAll(java.util.List.of(launcher, "--config", config.toString()));
      } else {
        command.addAll(
            java.util.List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-preview",
                "--enable-native-access=ALL-UNNAMED",
                "--add-modules=jdk.incubator.vector",
                "-cp",
                System.getProperty("java.class.path"),
                MongotStandalone.class.getName(),
                "--config",
                config.toString()));
      }
      this.process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(this.log.toFile())
              .start();
      this.channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
      try {
        awaitStartup();
      } catch (Exception | Error e) {
        close();
        throw e;
      }
    }

    private void awaitStartup() throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (this.process.isAlive() && System.nanoTime() < until) {
        try {
          collection();
          return;
        } catch (StatusRuntimeException e) {
          this.channel.resetConnectBackoff();
          Thread.sleep(25);
        }
      }
      throw new AssertionError(
          "Standalone process did not become ready: " + Files.readString(this.log));
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub catalog() {
      return CatalogServiceGrpc.newBlockingStub(this.channel)
          .withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    private DocumentServiceGrpc.DocumentServiceBlockingStub documents() {
      return DocumentServiceGrpc.newBlockingStub(this.channel)
          .withDeadlineAfter(10, TimeUnit.SECONDS);
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

    private void ready(String indexId, long sequence) throws Exception {
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (this.process.isAlive() && System.nanoTime() < until) {
        var index = catalog().getIndex(GetIndexRequest.newBuilder().setIndexId(indexId).build());
        if (index.getLifecycleState().equals("READY")
            && index.getLive().getVisibleSequence() >= sequence) {
          return;
        }
        Thread.sleep(20);
      }
      throw new AssertionError("Standalone index did not recover: " + Files.readString(this.log));
    }

    private RawBsonDocument query(String indexId) {
      var response =
          QueryServiceGrpc.newBlockingStub(this.channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .search(
                  QueryRequest.newBuilder()
                      .setIndexId(indexId)
                      .setQueryBson(
                          bson(
                              "{text: {path: 'title', query: 'durableterm'}, returnStoredSource:"
                                  + " true}"))
                      .build());
      return new RawBsonDocument(response.getBatchBson().toByteArray());
    }

    private RawBsonDocument vector(String indexId) {
      var response =
          QueryServiceGrpc.newBlockingStub(this.channel)
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .vectorSearch(
                  QueryRequest.newBuilder()
                      .setIndexId(indexId)
                      .setQueryBson(
                          bson(
                              "{path: 'embedding', queryVector: [1.0, 0.0, 0.0], exact: true,"
                                  + " limit: 1}"))
                      .build());
      return new RawBsonDocument(response.getBatchBson().toByteArray());
    }

    private void kill() throws Exception {
      // With strace, the launcher is the tracer and its child is the JVM being crash-tested.
      var descendants = this.process.descendants().toList();
      descendants.forEach(ProcessHandle::destroyForcibly);
      if (descendants.isEmpty()) {
        this.process.destroyForcibly();
      }
      if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Killed process remained alive");
      }
      for (var child : descendants) {
        child.onExit().get(10, TimeUnit.SECONDS);
      }
    }

    @Override
    public void close() throws Exception {
      this.channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      if (this.process.isAlive()) {
        var descendants = this.process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        if (descendants.isEmpty()) {
          this.process.destroy();
        }
        if (!this.process.waitFor(20, TimeUnit.SECONDS)) {
          kill();
        }
      }
      if ("1".equals(System.getenv("MONGOT_STANDALONE_NETWORK_GUARD"))) {
        String trace = Files.readString(this.network);
        assertThat(trace).doesNotContain("htons(27017)");
        assertThat(trace).doesNotContain("htons(27018)");
        assertThat(trace).doesNotContain("htons(27019)");
      }
    }
  }
}
