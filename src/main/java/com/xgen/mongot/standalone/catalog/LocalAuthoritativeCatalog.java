package com.xgen.mongot.standalone.catalog;

import com.google.errorprone.annotations.Var;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import com.xgen.mongot.standalone.grpc.CollectionDescription;
import com.xgen.mongot.standalone.grpc.CreateCollectionRequest;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.ListIndexesRequest;
import com.xgen.mongot.standalone.grpc.ListIndexesResponse;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.store.StandaloneStore;
import com.xgen.mongot.standalone.store.StandaloneStore.CollectionRecord;
import com.xgen.mongot.standalone.store.StandaloneStore.IndexRecord;
import com.xgen.mongot.standalone.store.StandaloneStore.RequestRecord;
import com.xgen.mongot.standalone.store.StandaloneStore.Transaction;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bson.types.ObjectId;

/**
 * Durable desired state and management retries, independent of physical generation reconciliation.
 */
public final class LocalAuthoritativeCatalog {
  private static final String REQUEST_SCOPE = "catalog";
  private final StandaloneStore store;

  @FunctionalInterface
  private interface Mutation<T> {
    T apply(Transaction transaction) throws SQLException;
  }

  public LocalAuthoritativeCatalog(StandaloneStore store) {
    this.store = store;
  }

  /** Creates or resolves a local collection identity within the bounded catalog. */
  public CollectionDescription createCollection(String principal, CreateCollectionRequest request) {
    return mutate(
        principal,
        request.getRequestId(),
        "CreateCollection",
        request,
        CollectionDescription.parser(),
        transaction -> {
          validateName(request.getDatabase(), "database");
          validateName(request.getName(), "collection");
          var existing = transaction.collection(request.getDatabase(), request.getName());
          if (existing.isPresent()) {
            return describe(existing.get());
          }
          if (transaction.collections().size() >= 64) {
            throw Status.RESOURCE_EXHAUSTED
                .withDescription("Standalone supports 64 collections")
                .asRuntimeException();
          }
          String id = UUID.randomUUID().toString();
          transaction.createCollection(id, request.getDatabase(), request.getName());
          return describe(transaction.collection(id).orElseThrow());
        });
  }

  public CollectionDescription getCollection(String id) {
    validateCollectionId(id);
    return read(transaction -> describe(requireCollection(transaction, id)));
  }

  /**
   * Durably creates desired index state, or returns the original equivalent definition's identity.
   */
  public IndexDescription createIndex(String principal, CreateIndexRequest request) {
    return mutate(
        principal,
        request.getRequestId(),
        "CreateIndex",
        request,
        IndexDescription.parser(),
        transaction -> {
          validateCollectionId(request.getCollectionId());
          var collection = requireCollection(transaction, request.getCollectionId());
          var candidate =
              StandaloneDefinitionValidator.parse(
                  collection,
                  new ObjectId().toHexString(),
                  request.getName(),
                  request.getType(),
                  1,
                  request.getDefinitionBson().toByteArray());
          var existing =
              transaction.indexes(collection.id(), false).stream()
                  .filter(index -> index.name().equals(request.getName()))
                  .findFirst();
          if (existing.isPresent()) {
            var definition =
                StandaloneDefinitionValidator.restore(existing.get().normalizedDefinition());
            if (existing.get().type() == request.getTypeValue()
                && Arrays.equals(
                    StandaloneDefinitionValidator.normalize(definition),
                    candidate.normalizedUserDefinition())) {
              return describe(existing.get());
            }
            throw Status.ALREADY_EXISTS
                .withDescription("Index name has a different definition")
                .asRuntimeException();
          }
          if (transaction.allIndexes().stream().filter(index -> !index.dropped()).count() >= 64) {
            throw Status.RESOURCE_EXHAUSTED
                .withDescription("Standalone supports 64 active indexes")
                .asRuntimeException();
          }
          var index =
              new IndexRecord(
                  candidate.internal().getIndexId().toHexString(),
                  collection.id(),
                  request.getName(),
                  request.getTypeValue(),
                  1,
                  request.getDefinitionBson().toByteArray(),
                  StandaloneDefinitionValidator.encode(candidate.internal().toBson()),
                  false);
          validateCatalogWith(transaction, index);
          transaction.putIndex(index);
          return describe(index);
        });
  }

  /** Replaces the entire desired mapping under compare-and-set revision control. */
  public IndexDescription updateIndex(String principal, UpdateIndexRequest request) {
    return mutate(
        principal,
        request.getRequestId(),
        "UpdateIndex",
        request,
        IndexDescription.parser(),
        transaction -> {
          var existing = requireIndex(transaction, request.getIndexId(), false);
          checkRevision(existing, request.getExpectedRevision());
          var definition =
              StandaloneDefinitionValidator.parse(
                  requireCollection(transaction, existing.collectionId()),
                  existing.id(),
                  existing.name(),
                  IndexType.forNumber(existing.type()),
                  Math.addExact(existing.revision(), 1),
                  request.getDefinitionBson().toByteArray());
          var index =
              new IndexRecord(
                  existing.id(),
                  existing.collectionId(),
                  existing.name(),
                  existing.type(),
                  existing.revision() + 1,
                  request.getDefinitionBson().toByteArray(),
                  StandaloneDefinitionValidator.encode(definition.internal().toBson()),
                  false);
          validateCatalogWith(transaction, index);
          transaction.putIndex(index);
          return describe(index);
        });
  }

  /** Records durable deletion intent and retains a retry tombstone without deleting source data. */
  public IndexDescription dropIndex(String principal, DropIndexRequest request) {
    return mutate(
        principal,
        request.getRequestId(),
        "DropIndex",
        request,
        IndexDescription.parser(),
        transaction -> {
          var existing = requireIndex(transaction, request.getIndexId(), true);
          checkRevision(existing, request.getExpectedRevision());
          var dropped =
              new IndexRecord(
                  existing.id(),
                  existing.collectionId(),
                  existing.name(),
                  existing.type(),
                  existing.revision(),
                  existing.definition(),
                  existing.normalizedDefinition(),
                  true);
          transaction.putIndex(dropped);
          return describe(dropped);
        });
  }

  public IndexDescription getIndex(String id) {
    return read(transaction -> describe(requireIndex(transaction, id, true)));
  }

  /**
   * Best-effort keyset pagination over durable desired state, including failed/uninitialized
   * indexes.
   */
  public ListIndexesResponse listIndexes(ListIndexesRequest request) {
    validateCollectionId(request.getCollectionId());
    int size = request.getPageSize() == 0 ? 100 : request.getPageSize();
    if (size < 1 || size > 1000) {
      throw Status.INVALID_ARGUMENT
          .withDescription("page_size must be between 1 and 1000")
          .asRuntimeException();
    }
    @Var String after = "";
    if (!request.getPageToken().isEmpty()) {
      String prefix = request.getCollectionId() + ":";
      if (!request.getPageToken().startsWith(prefix)) {
        throw Status.INVALID_ARGUMENT
            .withDescription("Page token belongs to another collection")
            .asRuntimeException();
      }
      after = request.getPageToken().substring(prefix.length());
      validateIndexId(after);
    }
    String afterId = after;
    return read(
        transaction -> {
          requireCollection(transaction, request.getCollectionId());
          var indexes =
              transaction.indexes(request.getCollectionId(), false).stream()
                  .filter(index -> index.id().compareTo(afterId) > 0)
                  .limit((long) size + 1)
                  .toList();
          var result = ListIndexesResponse.newBuilder();
          indexes.stream()
              .limit(size)
              .map(LocalAuthoritativeCatalog::describe)
              .forEach(result::addIndexes);
          if (indexes.size() > size) {
            result.setNextPageToken(request.getCollectionId() + ":" + indexes.get(size - 1).id());
          }
          return result.build();
        });
  }

  private static void validateCatalogWith(Transaction transaction, IndexRecord candidate)
      throws SQLException {
    var definitions =
        new ArrayList<>(
            transaction.allIndexes().stream()
                .filter(index -> !index.dropped() && !index.id().equals(candidate.id()))
                .map(index -> StandaloneDefinitionValidator.restore(index.normalizedDefinition()))
                .toList());
    definitions.add(StandaloneDefinitionValidator.restore(candidate.normalizedDefinition()));
    StandaloneDefinitionValidator.validateCatalog(definitions);
  }

  private static void checkRevision(IndexRecord index, long expected) {
    if (expected < 1 || index.revision() != expected) {
      throw Status.ABORTED
          .withDescription("Index revision does not match expected_revision")
          .asRuntimeException();
    }
  }

  public static CollectionRecord requireCollection(Transaction transaction, String id)
      throws SQLException {
    return transaction
        .collection(id)
        .orElseThrow(
            () ->
                Status.NOT_FOUND.withDescription("Collection does not exist").asRuntimeException());
  }

  private static IndexRecord requireIndex(
      Transaction transaction, String id, boolean includeDropped) throws SQLException {
    validateIndexId(id);
    return transaction
        .index(id)
        .filter(index -> includeDropped || !index.dropped())
        .orElseThrow(
            () -> Status.NOT_FOUND.withDescription("Index does not exist").asRuntimeException());
  }

  private <T extends MessageLite> T mutate(
      String principal,
      String requestId,
      String operation,
      MessageLite request,
      Parser<T> parser,
      Mutation<T> mutation) {
    validateRequestId(requestId);
    byte[] payload = request.toByteArray();
    byte[] digest = digest(payload);
    RequestRecord outcome;
    try {
      outcome =
          this.store.transaction(
              transaction -> {
                var prior = transaction.request(principal, REQUEST_SCOPE, requestId);
                if (prior.isPresent()) {
                  verifyRetry(prior.get(), operation, digest);
                  return prior.get();
                }
                @Var byte[] response = new byte[0];
                @Var Status status = Status.OK;
                try {
                  response = mutation.apply(transaction).toByteArray();
                } catch (IllegalArgumentException e) {
                  status = Status.INVALID_ARGUMENT.withDescription(e.getMessage());
                } catch (StatusRuntimeException e) {
                  status = e.getStatus();
                }
                var result =
                    new RequestRecord(
                        principal,
                        REQUEST_SCOPE,
                        requestId,
                        operation,
                        digest,
                        payload,
                        List.of(),
                        true,
                        response,
                        status.getCode().value(),
                        status.getDescription() == null ? "" : status.getDescription(),
                        System.currentTimeMillis());
                transaction.createRequest(result);
                return result;
              });
    } catch (SQLException e) {
      throw Status.UNAVAILABLE
          .withDescription("Authoritative catalog transaction failed")
          .withCause(e)
          .asRuntimeException();
    }
    if (outcome.statusCode() != 0) {
      throw Status.fromCodeValue(outcome.statusCode())
          .withDescription(outcome.message())
          .asRuntimeException();
    }
    try {
      return parser.parseFrom(outcome.response());
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw Status.DATA_LOSS
          .withDescription("Unreadable durable management outcome")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private <T> T read(StandaloneStore.Work<T> work) {
    try {
      return this.store.read(work);
    } catch (SQLException e) {
      throw Status.UNAVAILABLE
          .withDescription("Authoritative catalog read failed")
          .withCause(e)
          .asRuntimeException();
    }
  }

  public static void verifyRetry(RequestRecord prior, String operation, byte[] digest) {
    if (!prior.operation().equals(operation) || !MessageDigest.isEqual(prior.digest(), digest)) {
      throw Status.INVALID_ARGUMENT
          .withDescription("request_id is already bound to different content")
          .asRuntimeException();
    }
  }

  public static byte[] digest(byte[] payload) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(payload);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is required by the Java runtime", e);
    }
  }

  public static void validateRequestId(String id) {
    if (id.isBlank() || id.length() > 256 || id.indexOf('\0') >= 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("request_id must contain 1 to 256 characters without NUL")
          .asRuntimeException();
    }
  }

  public static void validateCollectionId(String id) {
    try {
      if (!UUID.fromString(id).toString().equals(id)) {
        throw new IllegalArgumentException("Noncanonical UUID");
      }
    } catch (IllegalArgumentException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("collection_id must be a canonical UUID")
          .asRuntimeException();
    }
  }

  private static void validateIndexId(String id) {
    if (!ObjectId.isValid(id) || !new ObjectId(id).toHexString().equals(id)) {
      throw Status.INVALID_ARGUMENT
          .withDescription("index_id must be a canonical ObjectId")
          .asRuntimeException();
    }
  }

  private static void validateName(String name, String kind) {
    if (name.isBlank() || name.length() > 256 || name.indexOf('\0') >= 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription(kind + " name must contain 1 to 256 characters without NUL")
          .asRuntimeException();
    }
  }

  private static CollectionDescription describe(CollectionRecord collection) {
    return CollectionDescription.newBuilder()
        .setCollectionId(collection.id())
        .setDatabase(collection.database())
        .setName(collection.name())
        .setAcceptedSequence(collection.sequence())
        .build();
  }

  public static IndexDescription describe(IndexRecord index) {
    return IndexDescription.newBuilder()
        .setIndexId(index.id())
        .setCollectionId(index.collectionId())
        .setName(index.name())
        .setTypeValue(index.type())
        .setDesiredRevision(index.revision())
        .setDesiredDefinitionBson(ByteString.copyFrom(index.definition()))
        .setLifecycleState(index.dropped() ? "DROPPED" : "PENDING")
        .build();
  }
}
