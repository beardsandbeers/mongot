package com.xgen.mongot.standalone.runtime;

import com.google.protobuf.MessageLite;
import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.grpc.Mutation;
import com.xgen.mongot.standalone.grpc.WriteBatchRequest;
import com.xgen.mongot.standalone.grpc.WriteItemState;
import com.xgen.mongot.standalone.grpc.WriteRequest;
import com.xgen.mongot.standalone.grpc.WriteResponse;
import com.xgen.mongot.standalone.store.StandaloneStore.RequestRecord;
import io.grpc.Context;
import io.grpc.Status;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Durable acceptance on a worker, followed by asynchronous indexing barriers for captured targets.
 */
public final class DocumentWriteCoordinator {
  private final StandaloneRuntime runtime;

  public DocumentWriteCoordinator(StandaloneRuntime runtime) {
    this.runtime = runtime;
  }

  public CompletableFuture<WriteResponse> write(String principal, WriteRequest request) {
    if (!request.hasMutation()) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Write requires one mutation")
          .asRuntimeException();
    }
    return write(
        principal,
        request.getCollectionId(),
        request.getRequestId(),
        request,
        List.of(request.getMutation()),
        null);
  }

  public CompletableFuture<WriteResponse> write(String principal, WriteBatchRequest request) {
    return write(
        principal,
        request.getCollectionId(),
        request.getRequestId(),
        request,
        request.getMutationsList(),
        null);
  }

  private CompletableFuture<WriteResponse> write(
      String principal,
      String collectionId,
      String id,
      MessageLite request,
      List<Mutation> mutations,
      @org.jetbrains.annotations.Nullable String operation) {
    var pin = this.runtime.store().pinRequest(principal, collectionId, id);
    try {
      var result = writePinned(principal, collectionId, id, request, mutations, operation);
      result.whenComplete((ignored, error) -> pin.close());
      return result;
    } catch (RuntimeException | Error e) {
      pin.close();
      throw e;
    }
  }

  private CompletableFuture<WriteResponse> writePinned(
      String principal,
      String collectionId,
      String id,
      MessageLite request,
      List<Mutation> mutations,
      @org.jetbrains.annotations.Nullable String operation) {
    LocalAuthoritativeCatalog.validateCollectionId(collectionId);
    LocalAuthoritativeCatalog.validateRequestId(id);
    var context = Context.current();
    var manifest =
        this.runtime
            .ingestion()
            .coordinate(
                () -> {
                  try {
                    var prior =
                        this.runtime
                            .store()
                            .read(transaction -> transaction.request(principal, collectionId, id));
                    var targets =
                        prior.isPresent()
                            ? List.<String>of()
                            : this.runtime.ingestion().captureTargets(collectionId);
                    if (prior.isEmpty()) {
                      this.runtime.checkWriteAdmission(request.getSerializedSize());
                    }
                    if (operation != null) {
                      return this.runtime
                          .ledger()
                          .beginCommand(
                              principal,
                              operation,
                              collectionId,
                              id,
                              request.toByteArray(),
                              mutations,
                              targets);
                    }
                    return request instanceof WriteRequest single
                        ? this.runtime.ledger().begin(principal, single, targets)
                        : this.runtime
                            .ledger()
                            .begin(principal, (WriteBatchRequest) request, targets);
                  } catch (java.sql.SQLException e) {
                    throw Status.UNAVAILABLE
                        .withDescription("Cannot inspect durable write manifest")
                        .withCause(e)
                        .asRuntimeException();
                  }
                });
    var prior = get(principal, collectionId, id);
    if (prior.getComplete()) {
      return CompletableFuture.completedFuture(prior);
    }
    for (int ordinal = prior.getResultsCount(); ordinal < mutations.size(); ordinal++) {
      if (context.isCancelled()) {
        throw Status.CANCELLED.asRuntimeException();
      }
      int item = ordinal;
      boolean acceptedItem =
          this.runtime
              .ingestion()
              .coordinate(
                  () -> {
                    try {
                      this.runtime.ingestion().validateTargets(manifest.targets());
                    } catch (io.grpc.StatusRuntimeException e) {
                      if (e.getStatus().getCode() != Status.Code.FAILED_PRECONDITION) {
                        throw e;
                      }
                      this.runtime
                          .ledger()
                          .rejectRemaining(principal, collectionId, id, e.getStatus());
                      return false;
                    }
                    this.runtime.checkWriteAdmission(
                        (long) mutations.get(item).getBson().size() + 32768);
                    this.runtime.ledger().acceptItem(principal, collectionId, id, item);
                    return true;
                  });
      if (!acceptedItem) {
        break;
      }
    }
    this.runtime.ingestion().wakeConsumers();
    var accepted = this.runtime.ledger().get(principal, collectionId, id);
    long barrierSequence =
        accepted.getResultsList().stream()
            .filter(result -> result.getState() == WriteItemState.WRITE_ITEM_STATE_PENDING_INDEXING)
            .mapToLong(result -> result.getAcceptedSequence())
            .max()
            .orElse(0);
    if (accepted.getComplete()) {
      return CompletableFuture.completedFuture(accepted);
    }
    List<String> targets = manifest.targets();
    var barrier = this.runtime.ingestion().awaitTargets(targets, barrierSequence);
    var result = new CompletableFuture<WriteResponse>();
    barrier.whenComplete(
        (ignored, error) -> {
          if (result.isCancelled()) {
            return;
          }
          try {
            result.complete(refresh(principal, collectionId, id, targets));
          } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
          }
        });
    result.whenComplete(
        (ignored, error) -> {
          if (result.isCancelled()) {
            barrier.cancel(false);
          }
        });
    return result;
  }

  /** Executes a named document command through durable source acceptance. */
  public CompletableFuture<WriteResponse> command(
      String principal,
      String operation,
      String collectionId,
      String id,
      MessageLite original,
      List<Mutation> mutations) {
    return write(principal, collectionId, id, original, mutations, operation);
  }

  /** Looks up durable per-item outcomes for a legacy write or document command. */
  public WriteResponse get(String principal, String collectionId, String id) {
    LocalAuthoritativeCatalog.validateCollectionId(collectionId);
    LocalAuthoritativeCatalog.validateRequestId(id);
    try (var ignored = this.runtime.store().pinRequest(principal, collectionId, id)) {
      RequestRecord request =
          this.runtime
              .store()
              .read(transaction -> transaction.request(principal, collectionId, id))
              .orElseThrow(
                  () ->
                      Status.NOT_FOUND
                          .withDescription("Write request does not exist for this principal")
                          .asRuntimeException());
      if (request.operation().equals("DeleteByQuery")) {
        throw Status.FAILED_PRECONDITION
            .withDescription("Use GetDeleteByQueryResult for this request")
            .asRuntimeException();
      }
      return refresh(principal, collectionId, id, request.targets());
    } catch (java.sql.SQLException e) {
      throw Status.UNAVAILABLE
          .withDescription("Cannot inspect durable write result")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private WriteResponse refresh(
      String principal, String collectionId, String id, List<String> targets) {
    var response = this.runtime.ledger().get(principal, collectionId, id);
    for (var item : response.getResultsList()) {
      if (item.getState() == WriteItemState.WRITE_ITEM_STATE_PENDING_INDEXING) {
        this.runtime
            .ingestion()
            .targetOutcome(targets, item.getAcceptedSequence())
            .ifPresent(
                status ->
                    this.runtime
                        .ledger()
                        .finishItem(principal, collectionId, id, item.getOrdinal(), status));
      }
    }
    return this.runtime.ledger().get(principal, collectionId, id);
  }
}
