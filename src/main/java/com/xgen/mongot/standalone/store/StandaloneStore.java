package com.xgen.mongot.standalone.store;

import com.google.errorprone.annotations.Var;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Authoritative standalone state. A transaction contains source changes, logical log entries and
 * their immutable retry outcomes; Lucene is a recoverable projection of this database.
 *
 * <p>Transactions are serialized and must never wait for indexing. Source snapshots use separate
 * WAL readers, so a mapping build does not hold the acceptance lock. The exclusive directory lock
 * covers the entire process lifetime, including projection recovery and shutdown.
 */
public final class StandaloneStore implements Closeable {
  public static final int SCHEMA_VERSION = 3;
  private static final int APPLICATION_ID = 0x4d475354;

  public record CollectionRecord(String id, String database, String name, long sequence) {}

  public record IndexRecord(
      String id,
      String collectionId,
      String name,
      int type,
      long revision,
      byte[] definition,
      byte[] normalizedDefinition,
      boolean dropped) {}

  public record RequestRecord(
      String principal,
      String scope,
      String id,
      String operation,
      byte[] digest,
      byte[] payload,
      List<String> targets,
      boolean complete,
      byte[] response,
      int statusCode,
      String message,
      long createdAtMillis) {}

  public record MutationRecord(long sequence, byte[] encodedId, byte[] bson, boolean delete) {}

  public record RetentionPin(String owner, String collectionId, long sequence) {}

  // Persisted ordinals: append states only; never reorder them.
  public enum DeleteQueryState {
    SELECTED,
    INDEXING,
    DELETED,
    CONFLICT,
    MISSING,
    FAILED
  }

  public record DeleteQueryItem(
      int ordinal,
      byte[] encodedId,
      long sourceSequence,
      DeleteQueryState state,
      long acceptedSequence) {}

  public record DeleteQueryCounts(
      long matched,
      long selected,
      long indexing,
      long deleted,
      long conflicts,
      long missing,
      long failed,
      long acceptedSequence) {}

  private record RequestKey(String principal, String scope, String id) {}

  public record StoreStatistics(long logBytes, long requests, long pins, long sourceBytes) {}

  @FunctionalInterface
  public interface Work<T> {
    T run(Transaction transaction) throws SQLException;
  }

  private final Path path;
  private final FileChannel lockChannel;
  private final FileLock directoryLock;
  private final Connection connection;
  private final String instanceId;
  private final Map<RequestKey, Integer> pinnedRequests = new HashMap<>();
  private boolean closed;

  /**
   * Locks the data directory and opens or initializes its schema using durable SQLite WAL commits.
   */
  public StandaloneStore(Path dataPath) throws IOException, SQLException {
    Files.createDirectories(dataPath);
    this.path = dataPath.resolve("standalone.sqlite");
    this.lockChannel =
        FileChannel.open(
            dataPath.resolve("standalone.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = this.lockChannel.tryLock();
    } catch (OverlappingFileLockException e) {
      this.lockChannel.close();
      throw new IOException("Standalone data directory is already in use", e);
    }
    if (acquired == null) {
      this.lockChannel.close();
      throw new IOException("Standalone data directory is already in use");
    }
    this.directoryLock = acquired;
    @Var Connection opened = null;
    try {
      // Register explicitly: deploy-jar service-file merging must not determine store availability.
      Class.forName("org.sqlite.JDBC");
      opened = openConnection(this.path);
      initialize(opened);
      this.connection = opened;
      try (var statement = opened.createStatement();
          var rows = statement.executeQuery("SELECT value FROM metadata WHERE key='instance_id'")) {
        if (!rows.next()) {
          throw new SQLException("Standalone instance identity is missing");
        }
        this.instanceId = rows.getString(1);
        UUID.fromString(this.instanceId);
      }
    } catch (SQLException | ClassNotFoundException | RuntimeException e) {
      if (opened != null) {
        try {
          opened.close();
        } catch (SQLException closeFailure) {
          e.addSuppressed(closeFailure);
        }
      }
      this.directoryLock.close();
      this.lockChannel.close();
      throw new IOException("Cannot open authoritative standalone store", e);
    }
  }

  private static Connection openConnection(Path path) throws SQLException {
    var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout=5000");
      statement.execute("PRAGMA foreign_keys=ON");
      statement.execute("PRAGMA synchronous=FULL");
    } catch (SQLException e) {
      connection.close();
      throw e;
    }
    return connection;
  }

  private static void initialize(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      int version;
      try (var rows = statement.executeQuery("PRAGMA user_version")) {
        rows.next();
        version = rows.getInt(1);
      }
      int application;
      try (var rows = statement.executeQuery("PRAGMA application_id")) {
        rows.next();
        application = rows.getInt(1);
      }
      if (version != 0
          && (version < 1 || version > SCHEMA_VERSION || application != APPLICATION_ID)) {
        throw new SQLException("Unsupported standalone store schema or application");
      }
      if (version == 0) {
        try (var rows =
            statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
          rows.next();
          if (rows.getInt(1) != 0 || application != 0) {
            throw new SQLException("Refusing to initialize an unrecognized database");
          }
        }
      }
      try (var rows = statement.executeQuery("PRAGMA journal_mode=WAL")) {
        rows.next();
        if (!rows.getString(1).equalsIgnoreCase("wal")) {
          throw new SQLException("Standalone store requires SQLite WAL mode");
        }
      }
      if (version == 0) {
        connection.setAutoCommit(false);
        try {
          statement.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
          try (var insert =
              connection.prepareStatement("INSERT INTO metadata VALUES ('instance_id', ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.executeUpdate();
          }
          statement.execute(
              "CREATE TABLE collections (id TEXT PRIMARY KEY, database_name TEXT NOT NULL,"
                  + " name TEXT NOT NULL, sequence INTEGER NOT NULL DEFAULT 0,"
                  + " UNIQUE(database_name, name))");
          statement.execute(
              "CREATE TABLE indexes (id TEXT PRIMARY KEY, collection_id TEXT NOT NULL"
                  + " REFERENCES collections(id), name TEXT NOT NULL, type INTEGER NOT NULL,"
                  + " revision INTEGER NOT NULL, definition BLOB NOT NULL,"
                  + " normalized_definition BLOB NOT NULL, dropped INTEGER NOT NULL DEFAULT 0)");
          statement.execute(
              "CREATE UNIQUE INDEX index_names ON indexes(collection_id, name) WHERE dropped=0");
          statement.execute(
              "CREATE TABLE documents (collection_id TEXT NOT NULL REFERENCES collections(id),"
                  + " encoded_id BLOB NOT NULL, bson BLOB NOT NULL, sequence INTEGER NOT NULL,"
                  + " PRIMARY KEY(collection_id, encoded_id)) WITHOUT ROWID");
          statement.execute(
              "CREATE TABLE mutations (collection_id TEXT NOT NULL REFERENCES collections(id),"
                  + " sequence INTEGER NOT NULL, encoded_id BLOB NOT NULL, bson BLOB NOT NULL,"
                  + " is_delete INTEGER NOT NULL, PRIMARY KEY(collection_id, sequence)) WITHOUT"
                  + " ROWID");
          statement.execute(
              "CREATE TABLE requests (principal TEXT NOT NULL, scope TEXT NOT NULL,"
                  + " request_id TEXT NOT NULL, operation TEXT NOT NULL, digest BLOB NOT NULL,"
                  + " payload BLOB NOT NULL, targets TEXT NOT NULL, complete INTEGER NOT NULL,"
                  + " response BLOB NOT NULL, status_code INTEGER NOT NULL, message TEXT NOT NULL,"
                  + " created_at INTEGER NOT NULL, PRIMARY KEY(principal, scope, request_id))"
                  + " WITHOUT ROWID");
          statement.execute(
              "CREATE TABLE request_items (principal TEXT NOT NULL, scope TEXT NOT NULL,"
                  + " request_id TEXT NOT NULL, ordinal INTEGER NOT NULL, outcome BLOB NOT NULL,"
                  + " PRIMARY KEY(principal, scope, request_id, ordinal),"
                  + " FOREIGN KEY(principal, scope, request_id)"
                  + " REFERENCES requests(principal, scope, request_id) ON DELETE CASCADE)"
                  + " WITHOUT ROWID");
          statement.execute("PRAGMA application_id=" + APPLICATION_ID);
          statement.execute("PRAGMA user_version=1");
          connection.commit();
        } catch (SQLException | RuntimeException e) {
          connection.rollback();
          throw e;
        } finally {
          connection.setAutoCommit(true);
        }
      }
      if (version < 2) {
        migrateRetention(connection);
      }
      if (version < 3) {
        migrateDeleteQueries(connection);
      }
      // The directory lock proves previous owners have stopped. Unregistered generations must
      // check the durable pruning floor and rebuild from source if their checkpoint is older.
      statement.executeUpdate("DELETE FROM retention_pins");
      try (var rows = statement.executeQuery("PRAGMA quick_check")) {
        if (!rows.next() || !"ok".equals(rows.getString(1))) {
          throw new SQLException("Standalone store integrity check failed");
        }
      }
    }
  }

  private static void migrateDeleteQueries(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE delete_query_items (principal TEXT NOT NULL, scope TEXT NOT NULL,"
              + " request_id TEXT NOT NULL, ordinal INTEGER NOT NULL, encoded_id BLOB NOT NULL,"
              + " source_sequence INTEGER NOT NULL, state INTEGER NOT NULL, accepted_sequence"
              + " INTEGER NOT NULL DEFAULT 0, status_code INTEGER NOT NULL DEFAULT 0, message TEXT"
              + " NOT NULL DEFAULT '', PRIMARY KEY(principal,scope,request_id,ordinal),"
              + " UNIQUE(principal,scope,request_id,encoded_id), FOREIGN"
              + " KEY(principal,scope,request_id) REFERENCES requests(principal,scope,request_id)"
              + " ON DELETE CASCADE) WITHOUT ROWID");
      statement.execute(
          "CREATE INDEX delete_query_pending ON delete_query_items"
              + " (principal,scope,request_id,state,ordinal)");
      statement.execute(
          "CREATE TABLE delete_query_stats (principal TEXT NOT NULL, scope TEXT NOT NULL,"
              + " request_id TEXT NOT NULL, matched INTEGER NOT NULL, selected INTEGER NOT NULL,"
              + " indexing INTEGER NOT NULL, deleted INTEGER NOT NULL, conflicts INTEGER NOT NULL,"
              + " missing INTEGER NOT NULL, failed INTEGER NOT NULL, accepted_sequence INTEGER NOT"
              + " NULL, PRIMARY KEY(principal,scope,request_id), FOREIGN"
              + " KEY(principal,scope,request_id) REFERENCES requests(principal,scope,request_id)"
              + " ON DELETE CASCADE) WITHOUT ROWID");
      statement.execute(
          "CREATE TRIGGER delete_query_added AFTER INSERT ON delete_query_items BEGIN INSERT INTO"
              + " delete_query_stats VALUES"
              + " (new.principal,new.scope,new.request_id,1,(new.state=0),(new.state=1),"
              + " (new.state=2),(new.state=3),(new.state=4),(new.state=5),new.accepted_sequence)"
              + " ON CONFLICT(principal,scope,request_id) DO UPDATE SET"
              + " matched=matched+1,selected=selected+(new.state=0),"
              + " indexing=indexing+(new.state=1),deleted=deleted+(new.state=2),"
              + " conflicts=conflicts+(new.state=3),missing=missing+(new.state=4),"
              + " failed=failed+(new.state=5),"
              + " accepted_sequence=max(accepted_sequence,new.accepted_sequence);"
              + " END");
      statement.execute(
          "CREATE TRIGGER delete_query_changed AFTER UPDATE OF state,accepted_sequence ON"
              + " delete_query_items BEGIN UPDATE delete_query_stats SET"
              + " selected=selected+(new.state=0)-(old.state=0),"
              + " indexing=indexing+(new.state=1)-(old.state=1),"
              + " deleted=deleted+(new.state=2)-(old.state=2),"
              + " conflicts=conflicts+(new.state=3)-(old.state=3),"
              + " missing=missing+(new.state=4)-(old.state=4),"
              + " failed=failed+(new.state=5)-(old.state=5),"
              + " accepted_sequence=max(accepted_sequence,new.accepted_sequence)"
              + " WHERE principal=new.principal AND scope=new.scope AND request_id=new.request_id;"
              + " END");
      statement.execute(
          "CREATE TRIGGER delete_query_removed AFTER DELETE ON delete_query_items BEGIN UPDATE"
              + " delete_query_stats SET"
              + " matched=matched-1,selected=selected-(old.state=0),"
              + " indexing=indexing-(old.state=1),deleted=deleted-(old.state=2),"
              + " conflicts=conflicts-(old.state=3),missing=missing-(old.state=4),"
              + " failed=failed-(old.state=5)"
              + " WHERE principal=old.principal AND scope=old.scope AND request_id=old.request_id;"
              + " END");
      statement.execute("PRAGMA user_version=3");
      connection.commit();
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private static void migrateRetention(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE retention_pins (owner TEXT PRIMARY KEY, collection_id TEXT NOT NULL"
              + " REFERENCES collections(id), sequence INTEGER NOT NULL)");
      statement.execute(
          "CREATE TABLE retention_floors (collection_id TEXT PRIMARY KEY REFERENCES"
              + " collections(id), sequence INTEGER NOT NULL)");
      statement.execute(
          "CREATE TABLE generation_history (generation_id TEXT PRIMARY KEY, collection_id TEXT"
              + " NOT NULL REFERENCES collections(id))");
      statement.execute(
          "CREATE TABLE store_statistics (name TEXT PRIMARY KEY, value INTEGER NOT NULL)");
      statement.execute(
          "INSERT INTO store_statistics SELECT 'log_bytes',"
              + " coalesce(sum(length(encoded_id)+length(bson)+32),0) FROM mutations");
      statement.execute(
          "CREATE TRIGGER mutation_added AFTER INSERT ON mutations BEGIN UPDATE store_statistics"
              + " SET value=value+length(new.encoded_id)+length(new.bson)+32"
              + " WHERE name='log_bytes'; END");
      statement.execute(
          "CREATE TRIGGER mutation_removed AFTER DELETE ON mutations BEGIN UPDATE store_statistics"
              + " SET value=value-length(old.encoded_id)-length(old.bson)-32"
              + " WHERE name='log_bytes'; END");
      statement.execute("CREATE INDEX request_expiration ON requests(created_at)");
      statement.execute("PRAGMA user_version=2");
      connection.commit();
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  /** Keeps an executing RPC's manifest alive even if its retry window expires while it waits. */
  public synchronized RequestPin pinRequest(String principal, String scope, String id) {
    var key = new RequestKey(principal, scope, id);
    this.pinnedRequests.merge(key, 1, Integer::sum);
    return new RequestPin(key);
  }

  public final class RequestPin implements AutoCloseable {
    private final RequestKey key;
    private boolean released;

    private RequestPin(RequestKey key) {
      this.key = key;
    }

    @Override
    public void close() {
      synchronized (StandaloneStore.this) {
        if (!this.released) {
          this.released = true;
          StandaloneStore.this.pinnedRequests.computeIfPresent(
              this.key, (unused, count) -> count == 1 ? null : count - 1);
        }
      }
    }
  }

  /** Prunes small transactional chunks; source state and collection sequences are never removed. */
  public synchronized void maintain(long requestsCreatedBeforeMillis) throws SQLException {
    transaction(
        transaction -> {
          transaction.pruneMutations(1024);
          for (var key : transaction.expiredRequests(requestsCreatedBeforeMillis, 256)) {
            if (!this.pinnedRequests.containsKey(key)) {
              transaction.update(
                  "DELETE FROM requests WHERE principal=? AND scope=? AND request_id=?",
                  key.principal(),
                  key.scope(),
                  key.id());
            }
          }
          return true;
        });
  }

  public String instanceId() {
    return this.instanceId;
  }

  public synchronized <T> T transaction(Work<T> work) throws SQLException {
    ensureOpen();
    this.connection.setAutoCommit(false);
    try {
      T result = work.run(new Transaction(this.connection, true));
      this.connection.commit();
      return result;
    } catch (SQLException | RuntimeException | Error e) {
      try {
        this.connection.rollback();
      } catch (SQLException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      throw e;
    } finally {
      this.connection.setAutoCommit(true);
    }
  }

  public synchronized <T> T read(Work<T> work) throws SQLException {
    ensureOpen();
    return work.run(new Transaction(this.connection, false));
  }

  public synchronized Snapshot snapshot(String collectionId) throws SQLException {
    ensureOpen();
    var reader = openConnection(this.path);
    try {
      reader.setAutoCommit(false);
      var collection = new Transaction(reader, false).collection(collectionId);
      if (collection.isEmpty()) {
        throw new SQLException("Snapshot collection does not exist");
      }
      // The first read fixes both the document view and its collection-local sequence.
      return new Snapshot(reader, collectionId, collection.get().sequence());
    } catch (SQLException | RuntimeException e) {
      reader.close();
      throw e;
    }
  }

  private void ensureOpen() throws SQLException {
    if (this.closed) {
      throw new SQLException("Standalone store is closed");
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (this.closed) {
      return;
    }
    this.closed = true;
    try {
      this.connection.close();
    } catch (SQLException e) {
      throw new IOException("Cannot close authoritative standalone store", e);
    } finally {
      this.directoryLock.close();
      this.lockChannel.close();
    }
  }

  public static final class Snapshot implements AutoCloseable {
    private final Connection reader;
    private final String collectionId;
    private final long sequence;

    private Snapshot(Connection reader, String collectionId, long sequence) {
      this.reader = reader;
      this.collectionId = collectionId;
      this.sequence = sequence;
    }

    public long sequence() {
      return this.sequence;
    }

    public void scan(Consumer<byte[]> consumer) throws SQLException {
      try (var statement =
          this.reader.prepareStatement("SELECT bson FROM documents WHERE collection_id=?")) {
        statement.setString(1, this.collectionId);
        try (var rows = statement.executeQuery()) {
          while (rows.next()) {
            consumer.accept(rows.getBytes(1));
          }
        }
      }
    }

    @Override
    public void close() throws SQLException {
      this.reader.close();
    }
  }

  /** A short-lived view, valid only during the enclosing store callback. */
  public static final class Transaction {
    private final Connection connection;
    private final boolean writable;

    private Transaction(Connection connection, boolean writable) {
      this.connection = connection;
      this.writable = writable;
    }

    private PreparedStatement prepare(String sql, Object... arguments) throws SQLException {
      var statement = this.connection.prepareStatement(sql);
      try {
        for (int i = 0; i < arguments.length; i++) {
          statement.setObject(i + 1, arguments[i]);
        }
        return statement;
      } catch (SQLException e) {
        statement.close();
        throw e;
      }
    }

    private int update(String sql, Object... arguments) throws SQLException {
      if (!this.writable) {
        throw new SQLException("Writes require a store transaction");
      }
      try (var statement = prepare(sql, arguments)) {
        return statement.executeUpdate();
      }
    }

    public Optional<CollectionRecord> collection(String id) throws SQLException {
      try (var statement = prepare("SELECT * FROM collections WHERE id=?", id);
          var rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(collectionRecord(rows)) : Optional.empty();
      }
    }

    public Optional<CollectionRecord> collection(String database, String name) throws SQLException {
      try (var statement =
              prepare(
                  "SELECT * FROM collections WHERE database_name=? AND name=?", database, name);
          var rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(collectionRecord(rows)) : Optional.empty();
      }
    }

    private static CollectionRecord collectionRecord(ResultSet row) throws SQLException {
      return new CollectionRecord(
          row.getString("id"),
          row.getString("database_name"),
          row.getString("name"),
          row.getLong("sequence"));
    }

    public List<CollectionRecord> collections() throws SQLException {
      try (var statement = prepare("SELECT * FROM collections ORDER BY id");
          var rows = statement.executeQuery()) {
        var collections = new ArrayList<CollectionRecord>();
        while (rows.next()) {
          collections.add(collectionRecord(rows));
        }
        return List.copyOf(collections);
      }
    }

    public void createCollection(String id, String database, String name) throws SQLException {
      update(
          "INSERT INTO collections(id, database_name, name) VALUES (?, ?, ?)", id, database, name);
    }

    public List<IndexRecord> indexes(String collectionId, boolean includeDropped)
        throws SQLException {
      try (var statement =
              prepare(
                  "SELECT * FROM indexes WHERE collection_id=?"
                      + (includeDropped ? "" : " AND dropped=0")
                      + " ORDER BY id",
                  collectionId);
          var rows = statement.executeQuery()) {
        var result = new ArrayList<IndexRecord>();
        while (rows.next()) {
          result.add(indexRecord(rows));
        }
        return List.copyOf(result);
      }
    }

    public List<IndexRecord> allIndexes() throws SQLException {
      try (var statement = prepare("SELECT * FROM indexes ORDER BY id");
          var rows = statement.executeQuery()) {
        var result = new ArrayList<IndexRecord>();
        while (rows.next()) {
          result.add(indexRecord(rows));
        }
        return List.copyOf(result);
      }
    }

    public Optional<IndexRecord> index(String id) throws SQLException {
      try (var statement = prepare("SELECT * FROM indexes WHERE id=?", id);
          var rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(indexRecord(rows)) : Optional.empty();
      }
    }

    private static IndexRecord indexRecord(ResultSet row) throws SQLException {
      return new IndexRecord(
          row.getString("id"),
          row.getString("collection_id"),
          row.getString("name"),
          row.getInt("type"),
          row.getLong("revision"),
          row.getBytes("definition"),
          row.getBytes("normalized_definition"),
          row.getBoolean("dropped"));
    }

    public void putIndex(IndexRecord index) throws SQLException {
      update(
          "INSERT INTO indexes VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET"
              + " revision=excluded.revision, definition=excluded.definition,"
              + " normalized_definition=excluded.normalized_definition, dropped=excluded.dropped",
          index.id(),
          index.collectionId(),
          index.name(),
          index.type(),
          index.revision(),
          index.definition(),
          index.normalizedDefinition(),
          index.dropped());
    }

    public Optional<byte[]> document(String collectionId, byte[] encodedId) throws SQLException {
      try (var statement =
              prepare(
                  "SELECT bson FROM documents WHERE collection_id=? AND encoded_id=?",
                  collectionId,
                  encodedId);
          var rows = statement.executeQuery()) {
        return rows.next() ? Optional.of(rows.getBytes(1)) : Optional.empty();
      }
    }

    public java.util.OptionalLong documentSequence(String collectionId, byte[] encodedId)
        throws SQLException {
      try (var statement =
              prepare(
                  "SELECT sequence FROM documents WHERE collection_id=? AND encoded_id=?",
                  collectionId,
                  encodedId);
          var rows = statement.executeQuery()) {
        return rows.next()
            ? java.util.OptionalLong.of(rows.getLong(1))
            : java.util.OptionalLong.empty();
      }
    }

    public void updateRequestProgress(String principal, String scope, String id, byte[] response)
        throws SQLException {
      if (update(
              "UPDATE requests SET response=? WHERE principal=? AND scope=? AND request_id=?",
              response,
              principal,
              scope,
              id)
          != 1) {
        throw new SQLException("Request does not exist");
      }
    }

    public void clearDeleteQuerySelection(String principal, String scope, String id)
        throws SQLException {
      update(
          "DELETE FROM delete_query_items WHERE principal=? AND scope=? AND request_id=?",
          principal,
          scope,
          id);
      update(
          "UPDATE delete_query_stats SET"
              + " matched=0,selected=0,indexing=0,deleted=0,conflicts=0,missing=0,"
              + " failed=0,accepted_sequence=0"
              + " WHERE principal=? AND scope=? AND request_id=?",
          principal,
          scope,
          id);
    }

    public boolean addDeleteQueryItem(
        String principal,
        String scope,
        String id,
        int ordinal,
        byte[] encodedId,
        long sourceSequence,
        DeleteQueryState state)
        throws SQLException {
      return update(
              "INSERT OR IGNORE INTO delete_query_items"
                  + " (principal,scope,request_id,ordinal,encoded_id,source_sequence,state) VALUES"
                  + " (?,?,?,?,?,?,?)",
              principal,
              scope,
              id,
              ordinal,
              encodedId,
              sourceSequence,
              state.ordinal())
          == 1;
    }

    /** Reads a bounded page of query-deletion items in their original selection order. */
    public List<DeleteQueryItem> deleteQueryItems(
        String principal, String scope, String id, DeleteQueryState state, int limit)
        throws SQLException {
      try (var statement =
              prepare(
                  "SELECT ordinal,encoded_id,source_sequence,state,accepted_sequence FROM"
                      + " delete_query_items WHERE principal=? AND scope=? AND request_id=? AND"
                      + " state=? ORDER BY ordinal LIMIT ?",
                  principal,
                  scope,
                  id,
                  state.ordinal(),
                  limit);
          var rows = statement.executeQuery()) {
        var result = new ArrayList<DeleteQueryItem>();
        while (rows.next()) {
          result.add(
              new DeleteQueryItem(
                  rows.getInt(1),
                  rows.getBytes(2),
                  rows.getLong(3),
                  DeleteQueryState.values()[rows.getInt(4)],
                  rows.getLong(5)));
        }
        return List.copyOf(result);
      }
    }

    public boolean updateDeleteQueryItem(
        String principal,
        String scope,
        String id,
        int ordinal,
        DeleteQueryState expected,
        DeleteQueryState state,
        long sequence,
        int statusCode,
        String message)
        throws SQLException {
      return update(
              "UPDATE delete_query_items SET state=?, accepted_sequence=CASE WHEN ?>0 THEN ? ELSE"
                  + " accepted_sequence END,status_code=?,message=? WHERE principal=? AND scope=?"
                  + " AND request_id=? AND ordinal=? AND state=?",
              state.ordinal(),
              sequence,
              sequence,
              statusCode,
              message,
              principal,
              scope,
              id,
              ordinal,
              expected.ordinal())
          == 1;
    }

    /** Reads transactionally maintained progress without scanning the selected target set. */
    public DeleteQueryCounts deleteQueryCounts(String principal, String scope, String id)
        throws SQLException {
      try (var statement =
              prepare(
                  "SELECT matched,selected,indexing,deleted,conflicts,missing,failed,"
                      + " accepted_sequence"
                      + " FROM delete_query_stats WHERE principal=? AND scope=? AND request_id=?",
                  principal,
                  scope,
                  id);
          var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return new DeleteQueryCounts(0, 0, 0, 0, 0, 0, 0, 0);
        }
        return new DeleteQueryCounts(
            rows.getLong(1),
            rows.getLong(2),
            rows.getLong(3),
            rows.getLong(4),
            rows.getLong(5),
            rows.getLong(6),
            rows.getLong(7),
            rows.getLong(8));
      }
    }

    public String deleteQueryFailure(String principal, String scope, String id)
        throws SQLException {
      try (var statement =
              prepare(
                  "SELECT message FROM delete_query_items WHERE principal=? AND scope=?"
                      + " AND request_id=? AND state=5 ORDER BY ordinal LIMIT 1",
                  principal,
                  scope,
                  id);
          var rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : "";
      }
    }

    /** Must share a transaction with its request-item acceptance outcome. */
    public long appendMutation(String collectionId, byte[] encodedId, byte[] bson, boolean delete)
        throws SQLException {
      if (update("UPDATE collections SET sequence=sequence+1 WHERE id=?", collectionId) != 1) {
        throw new SQLException("Mutation collection does not exist");
      }
      long sequence = collection(collectionId).orElseThrow().sequence();
      if (delete) {
        update(
            "DELETE FROM documents WHERE collection_id=? AND encoded_id=?",
            collectionId,
            encodedId);
      } else {
        update(
            "INSERT INTO documents VALUES (?, ?, ?, ?) ON CONFLICT(collection_id, encoded_id)"
                + " DO UPDATE SET bson=excluded.bson, sequence=excluded.sequence",
            collectionId,
            encodedId,
            bson,
            sequence);
      }
      update(
          "INSERT INTO mutations VALUES (?, ?, ?, ?, ?)",
          collectionId,
          sequence,
          encodedId,
          bson,
          delete);
      return sequence;
    }

    public List<MutationRecord> mutationsAfter(String collectionId, long sequence, int limit)
        throws SQLException {
      return mutationsAfter(collectionId, sequence, limit, Integer.MAX_VALUE);
    }

    /** Reads a bounded replay chunk; one oversized record is returned alone to ensure progress. */
    public List<MutationRecord> mutationsAfter(
        String collectionId, long sequence, int limit, int maxBytes) throws SQLException {
      try (var statement =
              prepare(
                  "SELECT * FROM mutations WHERE collection_id=? AND sequence>?"
                      + " ORDER BY sequence LIMIT ?",
                  collectionId,
                  sequence,
                  limit);
          var rows = statement.executeQuery()) {
        var result = new ArrayList<MutationRecord>();
        @Var long bytes = 0;
        while (rows.next()) {
          var mutation =
              new MutationRecord(
                  rows.getLong("sequence"),
                  rows.getBytes("encoded_id"),
                  rows.getBytes("bson"),
                  rows.getBoolean("is_delete"));
          long size = (long) mutation.encodedId().length + mutation.bson().length;
          if (!result.isEmpty() && bytes + size > maxBytes) {
            break;
          }
          result.add(mutation);
          bytes += size;
          if (bytes >= maxBytes) {
            break;
          }
        }
        return List.copyOf(result);
      }
    }

    /** Looks up the immutable manifest and original outcome in the caller's principal namespace. */
    public Optional<RequestRecord> request(String principal, String scope, String id)
        throws SQLException {
      try (var statement =
              prepare(
                  "SELECT * FROM requests WHERE principal=? AND scope=? AND request_id=?",
                  principal,
                  scope,
                  id);
          var rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        String targets = rows.getString("targets");
        return Optional.of(
            new RequestRecord(
                principal,
                scope,
                id,
                rows.getString("operation"),
                rows.getBytes("digest"),
                rows.getBytes("payload"),
                targets.isEmpty() ? List.of() : List.of(targets.split("\n")),
                rows.getBoolean("complete"),
                rows.getBytes("response"),
                rows.getInt("status_code"),
                rows.getString("message"),
                rows.getLong("created_at")));
      }
    }

    public void createRequest(RequestRecord request) throws SQLException {
      if (request.targets().stream()
          .anyMatch(target -> target.isEmpty() || target.contains("\n"))) {
        throw new IllegalArgumentException("Invalid target generation identity");
      }
      update(
          "INSERT INTO requests VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          request.principal(),
          request.scope(),
          request.id(),
          request.operation(),
          request.digest(),
          request.payload(),
          String.join("\n", request.targets()),
          request.complete(),
          request.response(),
          request.statusCode(),
          request.message(),
          request.createdAtMillis());
    }

    public void finishRequest(
        String principal, String scope, String id, byte[] response, int statusCode, String message)
        throws SQLException {
      if (update(
              "UPDATE requests SET complete=1, response=?, status_code=?, message=?"
                  + " WHERE principal=? AND scope=? AND request_id=?",
              response,
              statusCode,
              message,
              principal,
              scope,
              id)
          != 1) {
        throw new SQLException("Cannot finish a missing request");
      }
    }

    public List<byte[]> requestItems(String principal, String scope, String id)
        throws SQLException {
      try (var statement =
              prepare(
                  "SELECT outcome FROM request_items WHERE principal=? AND scope=? AND request_id=?"
                      + " ORDER BY ordinal",
                  principal,
                  scope,
                  id);
          var rows = statement.executeQuery()) {
        var result = new ArrayList<byte[]>();
        while (rows.next()) {
          result.add(rows.getBytes(1));
        }
        return List.copyOf(result);
      }
    }

    public void acceptItem(String principal, String scope, String id, int ordinal, byte[] outcome)
        throws SQLException {
      update(
          "INSERT INTO request_items VALUES (?, ?, ?, ?, ?)",
          principal,
          scope,
          id,
          ordinal,
          outcome);
    }

    public void updateItem(String principal, String scope, String id, int ordinal, byte[] outcome)
        throws SQLException {
      if (update(
              "UPDATE request_items SET outcome=?"
                  + " WHERE principal=? AND scope=? AND request_id=? AND ordinal=?",
              outcome,
              principal,
              scope,
              id,
              ordinal)
          != 1) {
        throw new SQLException("Cannot update a missing request item");
      }
    }

    public long logBytes() throws SQLException {
      try (var statement = prepare("SELECT value FROM store_statistics WHERE name='log_bytes'");
          var rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }

    public void pin(String owner, String collectionId, long sequence) throws SQLException {
      update(
          "INSERT INTO retention_pins VALUES (?, ?, ?) ON CONFLICT(owner) DO UPDATE SET"
              + " collection_id=excluded.collection_id, sequence=excluded.sequence",
          owner,
          collectionId,
          sequence);
    }

    public void markGenerationReady(String generationId, String collectionId) throws SQLException {
      update("INSERT OR IGNORE INTO generation_history VALUES (?, ?)", generationId, collectionId);
    }

    public boolean wasGenerationReady(String generationId) throws SQLException {
      try (var statement =
              prepare("SELECT 1 FROM generation_history WHERE generation_id=?", generationId);
          var rows = statement.executeQuery()) {
        return rows.next();
      }
    }

    public void unpin(String owner) throws SQLException {
      update("DELETE FROM retention_pins WHERE owner=?", owner);
    }

    public long prunedThrough(String collectionId) throws SQLException {
      try (var statement =
              prepare("SELECT sequence FROM retention_floors WHERE collection_id=?", collectionId);
          var rows = statement.executeQuery()) {
        return rows.next() ? rows.getLong(1) : 0;
      }
    }

    public List<RetentionPin> pins() throws SQLException {
      try (var statement = prepare("SELECT * FROM retention_pins");
          var rows = statement.executeQuery()) {
        var pins = new ArrayList<RetentionPin>();
        while (rows.next()) {
          pins.add(new RetentionPin(rows.getString(1), rows.getString(2), rows.getLong(3)));
        }
        return List.copyOf(pins);
      }
    }

    public StoreStatistics statistics() throws SQLException {
      try (var statement =
              prepare(
                  "SELECT (SELECT count(*) FROM requests), (SELECT count(*) FROM retention_pins),"
                      + " (SELECT coalesce(sum(length(bson)),0) FROM documents)");
          var rows = statement.executeQuery()) {
        rows.next();
        return new StoreStatistics(logBytes(), rows.getLong(1), rows.getLong(2), rows.getLong(3));
      }
    }

    private List<RequestKey> expiredRequests(long cutoff, int limit) throws SQLException {
      try (var statement =
              prepare(
                  "SELECT principal, scope, request_id FROM requests WHERE created_at<?"
                      + " ORDER BY created_at LIMIT ?",
                  cutoff,
                  limit);
          var rows = statement.executeQuery()) {
        var keys = new ArrayList<RequestKey>();
        while (rows.next()) {
          keys.add(new RequestKey(rows.getString(1), rows.getString(2), rows.getString(3)));
        }
        return keys;
      }
    }

    private void pruneMutations(int limit) throws SQLException {
      // Each collection is bounded separately. Pins are durable and advanced only after a real
      // commit, or while a consistent source snapshot owns the full prefix being replaced.
      for (var collection : collections()) {
        long floor;
        try (var statement =
                prepare(
                    "SELECT coalesce(min(sequence), ?) FROM retention_pins WHERE collection_id=?",
                    collection.sequence(),
                    collection.id());
            var rows = statement.executeQuery()) {
          rows.next();
          floor = rows.getLong(1);
        }
        update(
            "DELETE FROM mutations WHERE collection_id=? AND sequence IN (SELECT sequence FROM"
                + " mutations WHERE collection_id=? AND sequence<=? ORDER BY sequence LIMIT ?)",
            collection.id(),
            collection.id(),
            floor,
            limit);
        // A conservative floor may cover entries not yet physically removed by this chunk.
        update(
            "INSERT INTO retention_floors VALUES (?, ?) ON CONFLICT(collection_id) DO UPDATE"
                + " SET sequence=max(sequence,excluded.sequence)",
            collection.id(),
            floor);
      }
    }
  }
}
