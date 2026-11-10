package com.xgen.mongot.standalone.store;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.xgen.mongot.standalone.store.StandaloneStore.CollectionRecord;
import com.xgen.mongot.standalone.store.StandaloneStore.IndexRecord;
import com.xgen.mongot.standalone.store.StandaloneStore.MutationRecord;
import com.xgen.mongot.standalone.store.StandaloneStore.RequestRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StandaloneStoreTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void transaction_failure_rollsBackSourceLogAndOutcome() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      createCollection(store);

      assertThrows(
          SQLException.class,
          () ->
              store.transaction(
                  transaction -> {
                    transaction.createRequest(request());
                    transaction.appendMutation("collection", bytes("id"), bytes("document"), false);
                    transaction.acceptItem(
                        "principal", "collection", "request", 0, bytes("accepted"));
                    throw new SQLException("injected transaction failure");
                  }));

      assertThat(
              store
                  .<Optional<CollectionRecord>>read(
                      transaction -> transaction.collection("collection"))
                  .orElseThrow()
                  .sequence())
          .isEqualTo(0);
      assertThat(
              store.<Optional<byte[]>>read(
                  transaction -> transaction.document("collection", bytes("id"))))
          .isEmpty();
      assertThat(
              store.<List<MutationRecord>>read(
                  transaction -> transaction.mutationsAfter("collection", 0, 100)))
          .isEmpty();
      assertThat(
              store.<Optional<RequestRecord>>read(
                  transaction -> transaction.request("principal", "collection", "request")))
          .isEmpty();
    }
  }

  @Test
  public void reopen_committedMutation_preservesSourceLogOutcomeAndInstanceIdentity()
      throws Exception {
    String identity;
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      identity = store.instanceId();
      createCollection(store);
      store.transaction(
          transaction -> {
            transaction.createRequest(request());
            long sequence =
                transaction.appendMutation("collection", bytes("id"), bytes("document"), false);
            transaction.acceptItem(
                "principal", "collection", "request", 0, bytes("accepted:" + sequence));
            return null;
          });
    }

    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      assertThat(store.instanceId()).isEqualTo(identity);
      assertThat(
              store
                  .<Optional<byte[]>>read(
                      transaction -> transaction.document("collection", bytes("id")))
                  .orElseThrow())
          .isEqualTo(bytes("document"));
      var mutations =
          store.<List<MutationRecord>>read(
              transaction -> transaction.mutationsAfter("collection", 0, 100));
      assertThat(mutations).hasSize(1);
      assertThat(mutations.getFirst().sequence()).isEqualTo(1);
      assertThat(mutations.getFirst().bson()).isEqualTo(bytes("document"));
      var request =
          store.<Optional<RequestRecord>>read(
              transaction -> transaction.request("principal", "collection", "request"));
      assertThat(request.orElseThrow().targets()).containsExactly("generation");
      assertThat(request.orElseThrow().complete()).isFalse();
      assertThat(
              store
                  .read(
                      transaction -> transaction.requestItems("principal", "collection", "request"))
                  .getFirst())
          .isEqualTo(bytes("accepted:1"));
    }
  }

  @Test
  public void snapshot_concurrentReplaceAndDelete_preservesConsistentSourceView() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      createCollection(store);
      store.transaction(
          transaction ->
              transaction.appendMutation("collection", bytes("id"), bytes("old"), false));

      try (var snapshot = store.snapshot("collection")) {
        store.transaction(
            transaction ->
                transaction.appendMutation("collection", bytes("id"), bytes("new"), false));
        store.transaction(
            transaction ->
                transaction.appendMutation("collection", bytes("id"), bytes("delete"), true));
        var documents = new ArrayList<byte[]>();
        snapshot.scan(documents::add);

        assertThat(snapshot.sequence()).isEqualTo(1);
        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst()).isEqualTo(bytes("old"));
        assertThat(
                store.<Optional<byte[]>>read(
                    transaction -> transaction.document("collection", bytes("id"))))
            .isEmpty();
        assertThat(
                store.<List<MutationRecord>>read(
                    transaction -> transaction.mutationsAfter("collection", 1, 100)))
            .hasSize(2);
      }
    }
  }

  @Test
  public void open_directoryAlreadyOwned_failsWithoutDisturbingOwner() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      assertThrows(IOException.class, () -> new StandaloneStore(this.folder.getRoot().toPath()));

      createCollection(store);
      assertThat(
              store.<Optional<CollectionRecord>>read(
                  transaction -> transaction.collection("collection")))
          .isPresent();
    }

    try (var reopened = new StandaloneStore(this.folder.getRoot().toPath())) {
      assertThat(
              reopened.<Optional<CollectionRecord>>read(
                  transaction -> transaction.collection("collection")))
          .isPresent();
    }
  }

  @Test
  public void open_unknownSchema_failsClosedAndReleasesDirectoryLock() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      createCollection(store);
    }
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + this.folder.getRoot().toPath().resolve("standalone.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute("PRAGMA user_version=999");
    }

    assertThrows(IOException.class, () -> new StandaloneStore(this.folder.getRoot().toPath()));
    assertThrows(IOException.class, () -> new StandaloneStore(this.folder.getRoot().toPath()));
  }

  @Test
  public void dropIndex_sourceDocuments_retainsDocumentsAndTombstone() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      createCollection(store);
      store.transaction(
          transaction -> {
            transaction.putIndex(
                new IndexRecord(
                    "index", "collection", "name", 1, 1, bytes("{}"), bytes("{}"), false));
            transaction.appendMutation("collection", bytes("id"), bytes("source"), false);
            return null;
          });

      store.transaction(
          transaction -> {
            transaction.putIndex(
                new IndexRecord(
                    "index", "collection", "name", 1, 1, bytes("{}"), bytes("{}"), true));
            return null;
          });

      assertThat(
              store.<List<IndexRecord>>read(
                  transaction -> transaction.indexes("collection", false)))
          .isEmpty();
      assertThat(
              store.<List<IndexRecord>>read(transaction -> transaction.indexes("collection", true)))
          .hasSize(1);
      assertThat(
              store
                  .<Optional<byte[]>>read(
                      transaction -> transaction.document("collection", bytes("id")))
                  .orElseThrow())
          .isEqualTo(bytes("source"));
    }
  }

  @Test
  public void read_writeAttempt_rejectsWithoutChangingSource() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      assertThrows(
          SQLException.class,
          () ->
              store.read(
                  transaction -> {
                    transaction.createCollection("collection", "database", "name");
                    return null;
                  }));

      assertThat(
              store.<Optional<CollectionRecord>>read(
                  transaction -> transaction.collection("collection")))
          .isEmpty();
    }
  }

  private static void createCollection(StandaloneStore store) throws SQLException {
    store.transaction(
        transaction -> {
          transaction.createCollection("collection", "database", "name");
          return null;
        });
  }

  @Test
  public void pruning_slowestDurablePin_preservesRequiredSuffixAndSource() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      createCollection(store);
      store.transaction(
          transaction -> {
            transaction.appendMutation("collection", bytes("a"), bytes("one"), false);
            transaction.appendMutation("collection", bytes("a"), bytes("two"), false);
            transaction.appendMutation("collection", bytes("b"), bytes("three"), false);
            transaction.pin("live", "collection", 2);
            transaction.pin("build", "collection", 1);
            return null;
          });

      store.maintain(0);

      assertThat(store.<Long>read(transaction -> transaction.prunedThrough("collection")))
          .isEqualTo(1);
      assertThat(sequences(store)).containsExactly(2L, 3L).inOrder();
      assertThat(store.<Long>read(transaction -> transaction.logBytes())).isEqualTo(2 + 3 + 5 + 64);
      store.transaction(
          transaction -> {
            transaction.unpin("build");
            return null;
          });
      store.maintain(0);
      assertThat(sequences(store)).containsExactly(3L);
      assertThat(
              store
                  .<Optional<byte[]>>read(
                      transaction -> transaction.document("collection", bytes("a")))
                  .orElseThrow())
          .isEqualTo(bytes("two"));
    }
  }

  @Test
  public void pruning_runningSnapshot_preservesConcurrentReplaceAndDeleteSuffix() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      createCollection(store);
      store.transaction(
          transaction -> {
            transaction.appendMutation("collection", bytes("a"), bytes("old"), false);
            transaction.pin("snapshot", "collection", 0);
            return null;
          });
      try (var snapshot = store.snapshot("collection")) {
        store.transaction(
            transaction -> {
              transaction.pin("snapshot", "collection", snapshot.sequence());
              transaction.appendMutation("collection", bytes("a"), bytes("new"), false);
              transaction.appendMutation("collection", bytes("a"), bytes("delete"), true);
              return null;
            });
        store.maintain(0);
        var sources = new ArrayList<byte[]>();
        snapshot.scan(sources::add);

        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst()).isEqualTo(bytes("old"));
        assertThat(sequences(store)).containsExactly(2L, 3L).inOrder();
      }
    }
  }

  @Test
  public void reopen_previousPinOwner_releasesPinButRetainsDurableRebuildFloor() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      createCollection(store);
      store.transaction(
          transaction -> {
            transaction.appendMutation("collection", bytes("a"), bytes("source"), false);
            transaction.pin("generation", "collection", 1);
            return null;
          });
      store.maintain(0);
      assertThat(sequences(store)).isEmpty();
    }
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      assertThat(store.<List<StandaloneStore.RetentionPin>>read(transaction -> transaction.pins()))
          .isEmpty();
      assertThat(store.<Long>read(transaction -> transaction.prunedThrough("collection")))
          .isEqualTo(1);
      assertThat(store.<Long>read(transaction -> transaction.logBytes())).isEqualTo(0);
      assertThat(
              store.<Optional<byte[]>>read(
                  transaction -> transaction.document("collection", bytes("a"))))
          .isPresent();
    }
  }

  @Test
  public void retention_expiredRequest_preservesExecutingManifestUntilPinReleased()
      throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      createCollection(store);
      store.transaction(
          transaction -> {
            transaction.createRequest(request());
            transaction.acceptItem("principal", "collection", "request", 0, bytes("outcome"));
            return null;
          });
      try (var pin = store.pinRequest("principal", "collection", "request")) {
        store.maintain(2);
        assertThat(
                store.<Optional<RequestRecord>>read(
                    transaction -> transaction.request("principal", "collection", "request")))
            .isPresent();
      }
      store.maintain(2);
      assertThat(
              store.<Optional<RequestRecord>>read(
                  transaction -> transaction.request("principal", "collection", "request")))
          .isEmpty();
      assertThat(
              store.<List<byte[]>>read(
                  transaction -> transaction.requestItems("principal", "collection", "request")))
          .isEmpty();
    }
  }

  @Test
  public void open_versionOne_migratesAtomicallyWithoutChangingIdentityOrState() throws Exception {
    String identity;
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      identity = store.instanceId();
      createCollection(store);
      store.transaction(
          transaction ->
              transaction.appendMutation("collection", bytes("a"), bytes("source"), false));
    }
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + this.folder.getRoot().toPath().resolve("standalone.sqlite"));
        var statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER mutation_added");
      statement.execute("DROP TRIGGER mutation_removed");
      statement.execute("DROP TABLE retention_pins");
      statement.execute("DROP TABLE retention_floors");
      statement.execute("DROP TABLE generation_history");
      statement.execute("DROP TABLE store_statistics");
      statement.execute("DROP INDEX request_expiration");
      statement.execute("PRAGMA user_version=1");
    }
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      assertThat(store.instanceId()).isEqualTo(identity);
      assertThat(sequences(store)).containsExactly(1L);
      assertThat(store.<Long>read(transaction -> transaction.logBytes())).isEqualTo(1 + 6 + 32);
      store.maintain(0);
      assertThat(store.<Long>read(transaction -> transaction.logBytes())).isEqualTo(0);
    }
  }

  private static List<Long> sequences(StandaloneStore store) throws SQLException {
    return store.read(transaction -> transaction.mutationsAfter("collection", 0, 100)).stream()
        .map(MutationRecord::sequence)
        .toList();
  }

  private static RequestRecord request() {
    return new RequestRecord(
        "principal",
        "collection",
        "request",
        "Write",
        bytes("digest"),
        bytes("manifest"),
        List.of("generation"),
        false,
        new byte[0],
        0,
        "",
        1);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
