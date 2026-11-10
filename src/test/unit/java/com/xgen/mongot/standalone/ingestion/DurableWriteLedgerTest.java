package com.xgen.mongot.standalone.ingestion;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.index.lucene.util.LuceneDocumentIdEncoder;
import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.grpc.CreateCollectionRequest;
import com.xgen.mongot.standalone.grpc.Mutation;
import com.xgen.mongot.standalone.grpc.MutationType;
import com.xgen.mongot.standalone.grpc.WriteBatchRequest;
import com.xgen.mongot.standalone.grpc.WriteItemState;
import com.xgen.mongot.standalone.grpc.WriteRequest;
import com.xgen.mongot.standalone.store.StandaloneStore;
import com.xgen.mongot.standalone.store.StandaloneStore.CollectionRecord;
import com.xgen.mongot.standalone.store.StandaloneStore.RequestRecord;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DurableWriteLedgerTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void acceptItem_durableAcceptance_remainsPendingUntilIndexingFinishes() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      var request =
          write(collection(store), "write", MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}");
      ledger.begin("user", request, List.of("generation"));

      var accepted = ledger.acceptItem("user", request.getCollectionId(), "write", 0);

      assertThat(accepted.getAcceptedSequence()).isEqualTo(1);
      assertThat(accepted.getState()).isEqualTo(WriteItemState.WRITE_ITEM_STATE_PENDING_INDEXING);
      assertThat(ledger.get("user", request.getCollectionId(), "write").getComplete()).isFalse();

      ledger.finishItem("user", request.getCollectionId(), "write", 0, Status.OK);

      var completed = ledger.get("user", request.getCollectionId(), "write");
      assertThat(completed.getComplete()).isTrue();
      assertThat(completed.getResults(0).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXED);
    }
  }

  @Test
  public void insert_existingId_rejectsWithoutReplacingSourceOrConsumingSequence()
      throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      String collection = collection(store);
      var first =
          write(
              collection,
              "first",
              MutationType.MUTATION_TYPE_INSERT,
              "{_id: 'a', value: 'original'}");
      var duplicate =
          write(
              collection,
              "duplicate",
              MutationType.MUTATION_TYPE_INSERT,
              "{_id: 'a', value: 'replacement'}");
      ledger.begin("user", first, List.of("generation"));
      ledger.acceptItem("user", collection, "first", 0);
      ledger.begin("user", duplicate, List.of("generation"));

      var rejected = ledger.acceptItem("user", collection, "duplicate", 0);

      assertThat(rejected.hasAcceptedSequence()).isFalse();
      assertThat(rejected.getStatusCode()).isEqualTo(Status.Code.ALREADY_EXISTS.value());
      assertThat(source(store, collection).getString("value").getValue()).isEqualTo("original");
      assertThat(
              store
                  .<Optional<CollectionRecord>>read(
                      transaction -> transaction.collection(collection))
                  .orElseThrow()
                  .sequence())
          .isEqualTo(1);
    }
  }

  @Test
  public void upsert_existingDocument_replacesAllFields() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      String collection = collection(store);
      ledger.begin(
          "user",
          write(collection, "first", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a', old: true}"),
          List.of("generation"));
      ledger.acceptItem("user", collection, "first", 0);
      ledger.begin(
          "user",
          write(collection, "second", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a', fresh: true}"),
          List.of("generation"));

      ledger.acceptItem("user", collection, "second", 0);

      assertThat(source(store, collection))
          .isEqualTo(BsonDocument.parse("{_id: 'a', fresh: true}"));
    }
  }

  @Test
  public void retry_afterNewerDelete_doesNotResurrectOldUpsert() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      String collection = collection(store);
      var old =
          write(collection, "old", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a', value: 'old'}");
      ledger.begin("user", old, List.of("generation"));
      ledger.acceptItem("user", collection, "old", 0);
      ledger.finishItem("user", collection, "old", 0, Status.OK);
      ledger.begin(
          "user",
          write(collection, "delete", MutationType.MUTATION_TYPE_DELETE, "{_id: 'a'}"),
          List.of("generation"));
      ledger.acceptItem("user", collection, "delete", 0);

      var originalManifest = ledger.begin("user", old, List.of("new-generation"));
      var originalOutcome = ledger.acceptItem("user", collection, "old", 0);

      assertThat(originalManifest.targets()).containsExactly("generation");
      assertThat(originalOutcome.getAcceptedSequence()).isEqualTo(1);
      assertThat(originalOutcome.getState()).isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXED);
      assertThat(
              store.<Optional<byte[]>>read(
                  transaction -> transaction.document(collection, encodedId())))
          .isEmpty();
      assertThat(
              store
                  .<Optional<CollectionRecord>>read(
                      transaction -> transaction.collection(collection))
                  .orElseThrow()
                  .sequence())
          .isEqualTo(2);
    }
  }

  @Test
  public void batch_restartHalfway_resumesTailAndPreservesOriginalRejection() throws Exception {
    String collection;
    WriteBatchRequest batch;
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      collection = collection(store);
      batch =
          WriteBatchRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("batch")
              .addMutations(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}"))
              .addMutations(
                  mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a', duplicate: true}"))
              .addMutations(mutation(MutationType.MUTATION_TYPE_UPSERT, "{_id: 'b'}"))
              .build();
      ledger.begin("user", batch, List.of("generation"));
      ledger.acceptItem("user", collection, "batch", 0);
      ledger.acceptItem("user", collection, "batch", 1);
      ledger.begin(
          "user",
          write(collection, "delete", MutationType.MUTATION_TYPE_DELETE, "{_id: 'a'}"),
          List.of("generation"));
      ledger.acceptItem("user", collection, "delete", 0);
    }

    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      ledger.begin("user", batch, List.of("generation"));
      var originalSuccess = ledger.acceptItem("user", collection, "batch", 0);
      var originalRejection = ledger.acceptItem("user", collection, "batch", 1);
      var tail = ledger.acceptItem("user", collection, "batch", 2);

      assertThat(originalSuccess.getAcceptedSequence()).isEqualTo(1);
      assertThat(originalRejection.getStatusCode()).isEqualTo(Status.Code.ALREADY_EXISTS.value());
      assertThat(tail.getAcceptedSequence()).isEqualTo(3);
      assertThat(
              store.<Optional<byte[]>>read(
                  transaction -> transaction.document(collection, encodedId())))
          .isEmpty();
      assertThat(ledger.get("user", collection, "batch").getComplete()).isFalse();
    }
  }

  @Test
  public void retry_modifiedUnfinishedBatchTail_rejectsEntireRetry() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      String collection = collection(store);
      var batch =
          WriteBatchRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("batch")
              .addMutations(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}"))
              .addMutations(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'b'}"))
              .build();
      ledger.begin("user", batch, List.of("generation"));
      ledger.acceptItem("user", collection, "batch", 0);

      var error =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  ledger.begin(
                      "user",
                      batch.toBuilder()
                          .setMutations(
                              1, mutation(MutationType.MUTATION_TYPE_DELETE, "{_id: 'a'}"))
                          .build(),
                      List.of("generation")));

      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(
              store
                  .<Optional<CollectionRecord>>read(
                      transaction -> transaction.collection(collection))
                  .orElseThrow()
                  .sequence())
          .isEqualTo(1);
    }
  }

  @Test
  public void get_otherPrincipal_cannotReadWriteOutcomes() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      String collection = collection(store);
      ledger.begin(
          "alice",
          write(collection, "write", MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}"),
          List.of("generation"));
      ledger.acceptItem("alice", collection, "write", 0);

      var error =
          assertThrows(StatusRuntimeException.class, () -> ledger.get("bob", collection, "write"));

      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }
  }

  @Test
  public void finishItem_indexingFailure_preservesAcceptedSourceAndSequence() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      String collection = collection(store);
      ledger.begin(
          "user",
          write(collection, "write", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a'}"),
          List.of("generation"));
      ledger.acceptItem("user", collection, "write", 0);

      ledger.finishItem(
          "user",
          collection,
          "write",
          0,
          Status.INTERNAL.withDescription("target generation failed"));

      var result = ledger.get("user", collection, "write");
      assertThat(result.getComplete()).isTrue();
      assertThat(result.getResults(0).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_INDEXING_FAILED);
      assertThat(result.getResults(0).getAcceptedSequence()).isEqualTo(1);
      assertThat(source(store, collection)).isEqualTo(BsonDocument.parse("{_id: 'a'}"));
    }
  }

  @Test
  public void begin_noReadyTarget_rejectsBeforeCreatingManifest() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      String collection = collection(store);

      var error =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  ledger.begin(
                      "user",
                      write(collection, "write", MutationType.MUTATION_TYPE_UPSERT, "{_id: 'a'}"),
                      List.of()));

      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(
              store.<Optional<RequestRecord>>read(
                  transaction -> transaction.request("user", collection, "write")))
          .isEmpty();
    }
  }

  @Test
  public void rejectRemaining_retiredTarget_preservesAcceptedPrefixAndRejectsTailDurably()
      throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var ledger = ledger(store);
      String collection = collection(store);
      var request =
          WriteBatchRequest.newBuilder()
              .setCollectionId(collection)
              .setRequestId("batch")
              .addMutations(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'a'}"))
              .addMutations(mutation(MutationType.MUTATION_TYPE_INSERT, "{_id: 'b'}"))
              .build();
      ledger.begin("user", request, List.of("generation"));
      ledger.acceptItem("user", collection, "batch", 0);

      ledger.rejectRemaining(
          "user",
          collection,
          "batch",
          Status.FAILED_PRECONDITION.withDescription("target retired"));
      ledger.finishItem("user", collection, "batch", 0, Status.OK);
      var result = ledger.get("user", collection, "batch");

      assertThat(result.getComplete()).isTrue();
      assertThat(result.getResults(0).getAcceptedSequence()).isEqualTo(1);
      assertThat(result.getResults(1).getState())
          .isEqualTo(WriteItemState.WRITE_ITEM_STATE_REJECTED);
      assertThat(result.getResults(1).hasAcceptedSequence()).isFalse();
      assertThat(
              store
                  .<Optional<CollectionRecord>>read(
                      transaction -> transaction.collection(collection))
                  .orElseThrow()
                  .sequence())
          .isEqualTo(1);
      assertThat(ledger.acceptItem("user", collection, "batch", 1)).isEqualTo(result.getResults(1));
    }
  }

  private static DurableWriteLedger ledger(StandaloneStore store) {
    return new DurableWriteLedger(store, DurableWriteLedger.Limits.defaults());
  }

  private static String collection(StandaloneStore store) {
    return new LocalAuthoritativeCatalog(store)
        .createCollection(
            "user",
            CreateCollectionRequest.newBuilder()
                .setRequestId("collection")
                .setDatabase("database")
                .setName("collection")
                .build())
        .getCollectionId();
  }

  private static WriteRequest write(String collection, String id, MutationType type, String json) {
    return WriteRequest.newBuilder()
        .setCollectionId(collection)
        .setRequestId(id)
        .setMutation(mutation(type, json))
        .build();
  }

  private static Mutation mutation(MutationType type, String json) {
    return Mutation.newBuilder()
        .setType(type)
        .setBson(
            ByteString.copyFrom(StandaloneDefinitionValidator.encode(BsonDocument.parse(json))))
        .build();
  }

  private static byte[] encodedId() {
    return LuceneDocumentIdEncoder.encodeDocumentId(new BsonString("a"));
  }

  private static BsonDocument source(StandaloneStore store, String collection) throws Exception {
    return new org.bson.RawBsonDocument(
        store
            .<Optional<byte[]>>read(transaction -> transaction.document(collection, encodedId()))
            .orElseThrow());
  }
}
