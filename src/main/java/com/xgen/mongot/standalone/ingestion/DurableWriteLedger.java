package com.xgen.mongot.standalone.ingestion;

import com.google.errorprone.annotations.Var;
import com.google.protobuf.InvalidProtocolBufferException;
import com.xgen.mongot.standalone.StandaloneBson;
import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.grpc.Mutation;
import com.xgen.mongot.standalone.grpc.MutationResult;
import com.xgen.mongot.standalone.grpc.MutationType;
import com.xgen.mongot.standalone.grpc.WriteBatchRequest;
import com.xgen.mongot.standalone.grpc.WriteItemState;
import com.xgen.mongot.standalone.grpc.WriteRequest;
import com.xgen.mongot.standalone.grpc.WriteResponse;
import com.xgen.mongot.standalone.metrics.StandaloneMetrics;
import com.xgen.mongot.standalone.store.StandaloneStore;
import com.xgen.mongot.standalone.store.StandaloneStore.RequestRecord;
import com.xgen.mongot.standalone.store.StandaloneStore.Transaction;
import io.grpc.Status;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Ordered, non-atomic batch acceptance. The caller owns admission and target-generation fencing.
 * Acceptance is deliberately separate from indexing completion: no result here makes a pending
 * mutation a successful Write response.
 */
public final class DurableWriteLedger {
  public record Limits(
      int maxDocumentBytes, int maxDepth, int maxBatchDocuments, int maxBatchBytes) {
    public Limits {
      if (maxDocumentBytes < 5
          || maxDepth < 1
          || maxBatchDocuments < 1
          || maxBatchBytes < maxDocumentBytes) {
        throw new IllegalArgumentException("Invalid standalone document limits");
      }
    }

    public static Limits defaults() {
      return new Limits(16 * 1024 * 1024, 100, 1000, 32 * 1024 * 1024);
    }
  }

  private final StandaloneStore store;
  private final Limits limits;
  private final StandaloneMetrics metrics;

  public DurableWriteLedger(StandaloneStore store, Limits limits) {
    this(store, limits, StandaloneMetrics.disabled());
  }

  public DurableWriteLedger(StandaloneStore store, Limits limits, StandaloneMetrics metrics) {
    this.store = store;
    this.limits = limits;
    this.metrics = metrics;
  }

  public RequestRecord begin(String principal, WriteRequest request, List<String> targets) {
    if (!request.hasMutation()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Write requires one mutation")
          .asRuntimeException();
    }
    validateEnvelope(
        request.getCollectionId(), request.getRequestId(), List.of(request.getMutation()));
    validateMessageSize(request.getSerializedSize());
    return begin(
        principal,
        request.getCollectionId(),
        request.getRequestId(),
        "Write",
        request.toByteArray(),
        targets);
  }

  public RequestRecord begin(String principal, WriteBatchRequest request, List<String> targets) {
    validateEnvelope(request.getCollectionId(), request.getRequestId(), request.getMutationsList());
    validateMessageSize(request.getSerializedSize());
    return begin(
        principal,
        request.getCollectionId(),
        request.getRequestId(),
        "WriteBatch",
        request.toByteArray(),
        targets);
  }

  private RequestRecord begin(
      String principal,
      String collectionId,
      String id,
      String operation,
      byte[] payload,
      List<String> targets) {
    return transact(
        transaction -> {
          var prior = transaction.request(principal, collectionId, id);
          if (prior.isPresent()) {
            LocalAuthoritativeCatalog.verifyRetry(
                prior.get(), operation, LocalAuthoritativeCatalog.digest(payload));
            this.metrics.count("write.retries", 1, "operation", operation);
            return prior.get();
          }
          LocalAuthoritativeCatalog.requireCollection(transaction, collectionId);
          if (targets.isEmpty()) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Writes require a live ready index")
                .asRuntimeException();
          }
          var manifest =
              new RequestRecord(
                  principal,
                  collectionId,
                  id,
                  operation,
                  LocalAuthoritativeCatalog.digest(payload),
                  payload,
                  List.copyOf(targets),
                  false,
                  new byte[0],
                  0,
                  "",
                  System.currentTimeMillis());
          transaction.createRequest(manifest);
          return manifest;
        });
  }

  private void validateMessageSize(int size) {
    // Account for protobuf framing and names, while bounding unknown/forward-compatible fields.
    if ((long) size > (long) this.limits.maxBatchBytes() + 65536) {
      throw Status.RESOURCE_EXHAUSTED
          .withDescription("Protobuf request exceeds the configured transport allowance")
          .asRuntimeException();
    }
  }

  private void validateEnvelope(String collectionId, String requestId, List<Mutation> mutations) {
    LocalAuthoritativeCatalog.validateCollectionId(collectionId);
    LocalAuthoritativeCatalog.validateRequestId(requestId);
    if (mutations.isEmpty() || mutations.size() > this.limits.maxBatchDocuments()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Batch document count is outside the configured limit")
          .asRuntimeException();
    }
    long bytes = mutations.stream().mapToLong(mutation -> mutation.getBson().size()).sum();
    if (bytes > this.limits.maxBatchBytes()) {
      throw Status.RESOURCE_EXHAUSTED
          .withDescription("Batch bytes exceed the configured limit")
          .asRuntimeException();
    }
  }

  /** Returns a previously definitive outcome or accepts exactly the next item from the manifest. */
  public MutationResult acceptItem(String principal, String collectionId, String id, int ordinal) {
    long started = System.nanoTime();
    var accepted = new java.util.concurrent.atomic.AtomicBoolean();
    var type = new java.util.concurrent.atomic.AtomicReference<>("unspecified");
    var bytes = new java.util.concurrent.atomic.AtomicInteger();
    var completedOutcome =
        transact(
            transaction -> {
              var request = requireRequest(transaction, principal, collectionId, id);
              var mutations = mutations(request);
              var outcomes = outcomes(transaction, request);
              if (ordinal < 0 || ordinal >= mutations.size() || ordinal > outcomes.size()) {
                throw Status.FAILED_PRECONDITION
                    .withDescription("Items must be accepted in manifest order")
                    .asRuntimeException();
              }
              if (ordinal < outcomes.size()) {
                return outcomes.get(ordinal);
              }
              Mutation mutation = mutations.get(ordinal);
              type.set(mutation.getType().name());
              bytes.set(mutation.getBson().size());
              accepted.set(true);
              var result = MutationResult.newBuilder().setOrdinal(ordinal);
              @Var Optional<StandaloneBson.ValidatedMutation> validated;
              try {
                validated =
                    Optional.of(
                        StandaloneBson.mutation(
                            mutation, this.limits.maxDocumentBytes(), this.limits.maxDepth()));
              } catch (IllegalArgumentException e) {
                validated = Optional.empty();
                result
                    .setState(WriteItemState.WRITE_ITEM_STATE_REJECTED)
                    .setStatusCode(Status.Code.INVALID_ARGUMENT.value())
                    .setMessage(
                        Optional.ofNullable(e.getMessage()).orElse("Invalid BSON mutation"));
              }
              if (validated.isPresent()) {
                var document = validated.get();
                if (mutation.getType() == MutationType.MUTATION_TYPE_INSERT
                    && transaction.document(collectionId, document.encodedId()).isPresent()) {
                  result
                      .setState(WriteItemState.WRITE_ITEM_STATE_REJECTED)
                      .setStatusCode(Status.Code.ALREADY_EXISTS.value())
                      .setMessage("A source document with this encoded _id already exists");
                } else {
                  long sequence =
                      transaction.appendMutation(
                          collectionId,
                          document.encodedId(),
                          mutation.getBson().toByteArray(),
                          document.delete());
                  result
                      .setState(WriteItemState.WRITE_ITEM_STATE_PENDING_INDEXING)
                      .setAcceptedSequence(sequence);
                }
              }
              var outcome = result.build();
              transaction.acceptItem(principal, collectionId, id, ordinal, outcome.toByteArray());
              var response = response(transaction, request);
              if (response.getComplete()) {
                transaction.finishRequest(
                    principal, collectionId, id, response.toByteArray(), 0, "");
              }
              return outcome;
            });
    if (accepted.get()) {
      String outcome = completedOutcome.hasAcceptedSequence() ? "accepted" : "rejected";
      this.metrics.count("mutations", 1, "type", type.get(), "outcome", outcome);
      this.metrics.count("mutation.bytes", bytes.get(), "type", type.get(), "outcome", outcome);
      this.metrics.elapsed("acceptance", started, "type", type.get());
    }
    return completedOutcome;
  }

  public WriteResponse get(String principal, String collectionId, String id) {
    LocalAuthoritativeCatalog.validateCollectionId(collectionId);
    LocalAuthoritativeCatalog.validateRequestId(id);
    try {
      return this.store.read(
          transaction ->
              response(transaction, requireRequest(transaction, principal, collectionId, id)));
    } catch (SQLException e) {
      throw unavailable(e);
    }
  }

  /** Completes an unfinished manifest after permanent target retirement, without source changes. */
  public void rejectRemaining(String principal, String collectionId, String id, Status status) {
    if (status.isOk()) {
      throw new IllegalArgumentException("Tail rejection requires an error status");
    }
    transact(
        transaction -> {
          var request = requireRequest(transaction, principal, collectionId, id);
          int acceptedItems = outcomes(transaction, request).size();
          int total = mutations(request).size();
          for (int ordinal = acceptedItems; ordinal < total; ordinal++) {
            var rejected =
                MutationResult.newBuilder()
                    .setOrdinal(ordinal)
                    .setState(WriteItemState.WRITE_ITEM_STATE_REJECTED)
                    .setStatusCode(status.getCode().value())
                    .setMessage(
                        Optional.ofNullable(status.getDescription())
                            .orElse("Captured generation unavailable"))
                    .build();
            transaction.acceptItem(principal, collectionId, id, ordinal, rejected.toByteArray());
          }
          var response = response(transaction, request);
          if (response.getComplete()) {
            transaction.finishRequest(principal, collectionId, id, response.toByteArray(), 0, "");
          }
          return true;
        });
  }

  /** Persist terminal indexing state separately from the immutable source acceptance result. */
  public MutationResult finishItem(
      String principal, String collectionId, String id, int ordinal, Status indexingStatus) {
    return transact(
        transaction -> {
          var request = requireRequest(transaction, principal, collectionId, id);
          var results = outcomes(transaction, request);
          if (ordinal < 0 || ordinal >= results.size()) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Item has not been durably accepted")
                .asRuntimeException();
          }
          var result = results.get(ordinal);
          if (result.getState() != WriteItemState.WRITE_ITEM_STATE_PENDING_INDEXING) {
            return result;
          }
          var terminal =
              result.toBuilder()
                  .setState(
                      indexingStatus.isOk()
                          ? WriteItemState.WRITE_ITEM_STATE_INDEXED
                          : WriteItemState.WRITE_ITEM_STATE_INDEXING_FAILED)
                  .setStatusCode(indexingStatus.getCode().value())
                  .setMessage(Optional.ofNullable(indexingStatus.getDescription()).orElse(""))
                  .build();
          transaction.updateItem(principal, collectionId, id, ordinal, terminal.toByteArray());
          var response = response(transaction, request);
          if (response.getComplete()) {
            transaction.finishRequest(principal, collectionId, id, response.toByteArray(), 0, "");
          }
          return terminal;
        });
  }

  public int mutationCount(RequestRecord request) {
    return mutations(request).size();
  }

  private static List<Mutation> mutations(RequestRecord request) {
    try {
      return switch (request.operation()) {
        case "Write" -> List.of(WriteRequest.parseFrom(request.payload()).getMutation());
        case "WriteBatch" -> WriteBatchRequest.parseFrom(request.payload()).getMutationsList();
        default ->
            throw Status.DATA_LOSS
                .withDescription("Unknown durable write operation")
                .asRuntimeException();
      };
    } catch (InvalidProtocolBufferException e) {
      throw Status.DATA_LOSS
          .withDescription("Unreadable durable write manifest")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private static List<MutationResult> outcomes(Transaction transaction, RequestRecord request)
      throws SQLException {
    var bytes = transaction.requestItems(request.principal(), request.scope(), request.id());
    var results = new java.util.ArrayList<MutationResult>(bytes.size());
    try {
      for (byte[] item : bytes) {
        var result = MutationResult.parseFrom(item);
        if (result.getOrdinal() != results.size()) {
          throw Status.DATA_LOSS
              .withDescription("Noncontiguous durable batch outcomes")
              .asRuntimeException();
        }
        results.add(result);
      }
      return List.copyOf(results);
    } catch (InvalidProtocolBufferException e) {
      throw Status.DATA_LOSS
          .withDescription("Unreadable durable write outcome")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private static WriteResponse response(Transaction transaction, RequestRecord request)
      throws SQLException {
    var results = outcomes(transaction, request);
    boolean complete =
        results.size() == mutations(request).size()
            && results.stream()
                .noneMatch(
                    result ->
                        result.getState() == WriteItemState.WRITE_ITEM_STATE_PENDING_INDEXING);
    return WriteResponse.newBuilder()
        .setCollectionId(request.scope())
        .setRequestId(request.id())
        .addAllResults(results)
        .setComplete(complete)
        .build();
  }

  private static RequestRecord requireRequest(
      Transaction transaction, String principal, String collectionId, String id)
      throws SQLException {
    return transaction
        .request(principal, collectionId, id)
        .orElseThrow(
            () ->
                Status.NOT_FOUND
                    .withDescription("Write request does not exist for this principal")
                    .asRuntimeException());
  }

  private <T> T transact(StandaloneStore.Work<T> work) {
    try {
      return this.store.transaction(work);
    } catch (SQLException e) {
      throw unavailable(e);
    }
  }

  private static io.grpc.StatusRuntimeException unavailable(SQLException e) {
    return Status.UNAVAILABLE
        .withDescription("Authoritative write transaction failed")
        .withCause(e)
        .asRuntimeException();
  }
}
