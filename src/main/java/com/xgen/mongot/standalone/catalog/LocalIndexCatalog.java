package com.xgen.mongot.standalone.catalog;

import com.google.protobuf.ByteString;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import io.grpc.Status;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.types.ObjectId;

/** Persists index definitions only. Content is stored exclusively by Lucene. */
public final class LocalIndexCatalog implements Closeable {
  private final Path path;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final LinkedHashMap<String, IndexDefinition> indexes = new LinkedHashMap<>();

  /** Acquires exclusive ownership and loads only index definitions from disk. */
  public LocalIndexCatalog(Path directory) throws IOException {
    Files.createDirectories(directory);
    this.path = directory.resolve("indexes.json");
    this.lockChannel =
        FileChannel.open(
            directory.resolve("standalone.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      this.lock = this.lockChannel.tryLock();
    } catch (OverlappingFileLockException e) {
      this.lockChannel.close();
      throw new IOException("Standalone data directory is already in use", e);
    }
    if (this.lock == null) {
      this.lockChannel.close();
      throw new IOException("Standalone data directory is already in use");
    }
    try {
      if (Files.exists(directory.resolve("standalone.sqlite"))) {
        throw new IOException(
            "Legacy document-store state is unsupported. Use a new dataPath and reingest content.");
      }
      if (Files.exists(this.path)) {
        var definitions = BsonDocument.parse(Files.readString(this.path)).getArray("indexes");
        for (var value : definitions) {
          var definition =
              StandaloneDefinitionValidator.restore(
                  StandaloneDefinitionValidator.encode(value.asDocument()));
          if (this.indexes.put(definition.getIndexId().toHexString(), definition) != null) {
            throw new IOException("Duplicate index identity in catalog");
          }
        }
        StandaloneDefinitionValidator.validateCatalog(definitions());
      }
    } catch (IOException | RuntimeException e) {
      close();
      throw e;
    }
  }

  public synchronized List<IndexDefinition> definitions() {
    return List.copyOf(this.indexes.values());
  }

  /** Creates an empty index or resolves an equivalent existing definition by name. */
  public synchronized IndexDescription createIndex(CreateIndexRequest request) {
    var candidate =
        StandaloneDefinitionValidator.parse(
            new ObjectId().toHexString(),
            request.getName(),
            request.getType(),
            1,
            request.getDefinitionBson().toByteArray());
    for (var existing : this.indexes.values()) {
      if (existing.getName().equals(request.getName())) {
        if (Arrays.equals(
                StandaloneDefinitionValidator.normalize(existing),
                candidate.normalizedUserDefinition())
            && existing.getType() == candidate.internal().getType()) {
          return describe(existing);
        }
        throw Status.ALREADY_EXISTS
            .withDescription("Index name has a different definition")
            .asRuntimeException();
      }
    }
    if (this.indexes.size() >= 64) {
      throw Status.RESOURCE_EXHAUSTED
          .withDescription("Standalone supports 64 indexes")
          .asRuntimeException();
    }
    var updated = new LinkedHashMap<>(this.indexes);
    updated.put(candidate.internal().getIndexId().toHexString(), candidate.internal());
    save(updated);
    return describe(candidate.internal());
  }

  public synchronized IndexDescription updateIndex(UpdateIndexRequest request) {
    var existing = require(request.getIndexId());
    checkRevision(existing, request.getExpectedRevision());
    var candidate =
        StandaloneDefinitionValidator.parse(
            request.getIndexId(),
            existing.getName(),
            type(existing),
            request.getExpectedRevision() + 1,
            request.getDefinitionBson().toByteArray());
    var updated = new LinkedHashMap<>(this.indexes);
    updated.put(request.getIndexId(), candidate.internal());
    save(updated);
    return describe(candidate.internal());
  }

  public synchronized IndexDescription dropIndex(DropIndexRequest request) {
    var existing = require(request.getIndexId());
    checkRevision(existing, request.getExpectedRevision());
    var updated = new LinkedHashMap<>(this.indexes);
    updated.remove(request.getIndexId());
    save(updated);
    return describe(existing).toBuilder().setLifecycleState("DROPPED").build();
  }

  public synchronized IndexDescription getIndex(String id) {
    return describe(require(id));
  }

  /** Resolves an instance-wide name once; callers retain the returned stable identity. */
  public synchronized IndexDescription getIndexByName(String name) {
    return this.indexes.values().stream()
        .filter(index -> index.getName().equals(name))
        .findFirst()
        .map(LocalIndexCatalog::describe)
        .orElseThrow(
            () -> Status.NOT_FOUND.withDescription("Index does not exist").asRuntimeException());
  }

  private IndexDefinition require(String id) {
    validateIndexId(id);
    var definition = this.indexes.get(id);
    if (definition == null) {
      throw Status.NOT_FOUND.withDescription("Index does not exist").asRuntimeException();
    }
    return definition;
  }

  public static void validateIndexId(String id) {
    if (!ObjectId.isValid(id) || !new ObjectId(id).toHexString().equals(id)) {
      throw new IllegalArgumentException("index_id must be a canonical ObjectId hex string");
    }
  }

  private static void checkRevision(IndexDefinition definition, long expected) {
    if (expected < 1 || definition.getDefinitionVersion().orElse(1L) != expected) {
      throw Status.ABORTED.withDescription("Index revision changed").asRuntimeException();
    }
  }

  private void save(LinkedHashMap<String, IndexDefinition> updated) {
    StandaloneDefinitionValidator.validateCatalog(new ArrayList<>(updated.values()));
    byte[] bytes =
        new BsonDocument(
                "indexes",
                new BsonArray(updated.values().stream().map(IndexDefinition::toBson).toList()))
            .toJson()
            .getBytes(StandardCharsets.UTF_8);
    Path temporary = this.path.resolveSibling("indexes.json.tmp");
    try {
      try (var output =
          FileChannel.open(
              temporary,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          output.write(buffer);
        }
        output.force(true);
      }
      Files.move(
          temporary,
          this.path,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      try (var directory = FileChannel.open(this.path.getParent(), StandardOpenOption.READ)) {
        directory.force(true);
      }
      this.indexes.clear();
      this.indexes.putAll(updated);
    } catch (IOException e) {
      throw Status.UNAVAILABLE
          .withDescription("Cannot persist index definitions")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private static IndexType type(IndexDefinition definition) {
    return definition.getType() == IndexDefinition.Type.SEARCH
        ? IndexType.INDEX_TYPE_SEARCH
        : IndexType.INDEX_TYPE_VECTOR_SEARCH;
  }

  private static IndexDescription describe(IndexDefinition definition) {
    return IndexDescription.newBuilder()
        .setIndexId(definition.getIndexId().toHexString())
        .setName(definition.getName())
        .setType(type(definition))
        .setDesiredRevision(definition.getDefinitionVersion().orElse(1L))
        .setDesiredDefinitionBson(
            ByteString.copyFrom(StandaloneDefinitionValidator.normalize(definition)))
        .setLifecycleState("PENDING")
        .build();
  }

  public static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      this.lock.release();
    } finally {
      this.lockChannel.close();
    }
  }
}
