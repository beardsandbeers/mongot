package com.xgen.mongot.standalone.server;

import com.google.errorprone.annotations.Var;
import com.google.protobuf.ByteString;
import com.xgen.mongot.standalone.StandaloneBson;
import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.config.StandaloneConfig.Role;
import com.xgen.mongot.standalone.grpc.DeleteByQueryRequest;
import com.xgen.mongot.standalone.grpc.DeleteByQueryResult;
import com.xgen.mongot.standalone.grpc.DeleteRequest;
import com.xgen.mongot.standalone.grpc.DeleteResponse;
import com.xgen.mongot.standalone.grpc.DocumentId;
import com.xgen.mongot.standalone.grpc.DocumentWriteConcern;
import com.xgen.mongot.standalone.grpc.GetMoreRequest;
import com.xgen.mongot.standalone.grpc.Mutation;
import com.xgen.mongot.standalone.grpc.MutationType;
import com.xgen.mongot.standalone.grpc.QueryRequest;
import com.xgen.mongot.standalone.grpc.QueryResponse;
import com.xgen.mongot.standalone.grpc.SequenceCondition;
import com.xgen.mongot.standalone.grpc.UpsertRequest;
import com.xgen.mongot.standalone.grpc.UpsertResponse;
import com.xgen.mongot.standalone.grpc.WriteItemState;
import com.xgen.mongot.standalone.grpc.WriteResponse;
import com.xgen.mongot.standalone.runtime.DocumentWriteCoordinator;
import com.xgen.mongot.standalone.runtime.StandaloneRuntime;
import com.xgen.mongot.standalone.store.StandaloneStore;
import com.xgen.mongot.standalone.store.StandaloneStore.DeleteQueryItem;
import com.xgen.mongot.standalone.store.StandaloneStore.DeleteQueryState;
import com.xgen.mongot.standalone.store.StandaloneStore.RequestRecord;
import io.grpc.Context;
import io.grpc.Status;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.types.ObjectId;

/** Document commands and durable, version-conditional server-side delete-by-query. */
final class DocumentCommands implements AutoCloseable {
  private final StandaloneRuntime runtime;
  private final DocumentWriteCoordinator writes;
  private final StandaloneQueryService queries;
  private final ThreadPoolExecutor executor;
  private final ReentrantLock[] queryLocks = new ReentrantLock[64];

  DocumentCommands(
      StandaloneRuntime runtime, DocumentWriteCoordinator writes, StandaloneQueryService queries) {
    this.runtime = runtime;
    this.writes = writes;
    this.queries = queries;
    for (int i = 0; i < this.queryLocks.length; i++) {
      this.queryLocks[i] = new ReentrantLock();
    }
    this.executor =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(runtime.config().maxInFlightRequests()),
            Thread.ofPlatform().name("standalone-delete-query-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    runtime
        .metrics()
        .gauge("delete_query.active", this.executor, pool -> (double) pool.getActiveCount());
    runtime
        .metrics()
        .gauge("delete_query.queued", this.executor, pool -> (double) pool.getQueue().size());
  }

  CompletableFuture<UpsertResponse> upsert(String principal, UpsertRequest request) {
    condition(request.getConcern());
    var mutations =
        request.getDocumentsBsonList().stream()
            .map(
                document ->
                    Mutation.newBuilder()
                        .setType(MutationType.MUTATION_TYPE_UPSERT)
                        .setBson(document)
                        .build())
            .toList();
    return this.writes
        .command(
            principal,
            "Upsert",
            request.getCollectionId(),
            request.getRequestId(),
            request,
            mutations)
        .thenCompose(response -> finish(principal, response, request.getConcern()))
        .thenApply(
            response ->
                UpsertResponse.newBuilder()
                    .setCollectionId(response.getCollectionId())
                    .setRequestId(response.getRequestId())
                    .setUpsertedCount(response.getResultsCount())
                    .setAcceptedSequence(sequence(response))
                    .build());
  }

  CompletableFuture<DeleteResponse> delete(String principal, DeleteRequest request) {
    condition(request.getConcern());
    var mutations = request.getIdsList().stream().map(id -> deletion(value(id))).toList();
    return this.writes
        .command(
            principal,
            "Delete",
            request.getCollectionId(),
            request.getRequestId(),
            request,
            mutations)
        .thenCompose(response -> finish(principal, response, request.getConcern()))
        .thenApply(
            response ->
                DeleteResponse.newBuilder()
                    .setCollectionId(response.getCollectionId())
                    .setRequestId(response.getRequestId())
                    .setDeletedCount(
                        (int)
                            response.getResultsList().stream()
                                .filter(item -> item.getSourceExisted())
                                .count())
                    .setAcceptedSequence(sequence(response))
                    .build());
  }

  private CompletableFuture<WriteResponse> finish(
      String principal, WriteResponse response, DocumentWriteConcern concern) {
    if (!response.getComplete()) {
      throw Status.UNAVAILABLE
          .withDescription("Document command is incomplete; inspect GetWriteResult")
          .asRuntimeException();
    }
    for (var item : response.getResultsList()) {
      if (item.getState() != WriteItemState.WRITE_ITEM_STATE_INDEXED || item.getStatusCode() != 0) {
        @Var var status = Status.fromCodeValue(item.getStatusCode());
        if (status.isOk()) {
          status = Status.UNAVAILABLE;
        }
        throw status
            .withDescription(
                "Document command "
                    + response.getRequestId()
                    + ", item "
                    + item.getOrdinal()
                    + ": "
                    + item.getMessage()
                    + "; inspect GetWriteResult for partial outcomes")
            .asRuntimeException();
      }
    }
    var record = record(principal, response.getCollectionId(), response.getRequestId());
    if (record.message().equals("document-command:complete")) {
      return CompletableFuture.completedFuture(response);
    }
    var barrier =
        concern == DocumentWriteConcern.DOCUMENT_WRITE_CONCERN_INDEXED
            ? CompletableFuture.<Void>completedFuture(null)
            : this.runtime
                .ingestion()
                .awaitTargets(record.targets(), sequence(response), condition(concern));
    return barrier.thenApply(
        ignored -> {
          transaction(
              tx -> {
                tx.finishRequest(
                    principal,
                    response.getCollectionId(),
                    response.getRequestId(),
                    response.toByteArray(),
                    0,
                    "document-command:complete");
                return true;
              });
          return response;
        });
  }

  private static long sequence(WriteResponse response) {
    return response.getResultsList().stream()
        .filter(item -> item.hasAcceptedSequence())
        .mapToLong(item -> item.getAcceptedSequence())
        .max()
        .orElse(0);
  }

  private static SequenceCondition condition(DocumentWriteConcern concern) {
    return switch (concern) {
      case DOCUMENT_WRITE_CONCERN_VISIBLE -> SequenceCondition.SEQUENCE_CONDITION_VISIBLE;
      case DOCUMENT_WRITE_CONCERN_INDEXED -> SequenceCondition.SEQUENCE_CONDITION_APPLIED;
      case DOCUMENT_WRITE_CONCERN_COMMITTED -> SequenceCondition.SEQUENCE_CONDITION_COMMITTED;
      default ->
          throw Status.INVALID_ARGUMENT
              .withDescription("Unknown document write concern")
              .asRuntimeException();
    };
  }

  private static BsonValue value(DocumentId id) {
    return switch (id.getValueCase()) {
      case STRING_VALUE -> new BsonString(id.getStringValue());
      case OBJECT_ID -> {
        if (!ObjectId.isValid(id.getObjectId())) {
          throw Status.INVALID_ARGUMENT
              .withDescription("ObjectId must contain 24 hexadecimal characters")
              .asRuntimeException();
        }
        yield new BsonObjectId(new ObjectId(id.getObjectId()));
      }
      case INT32_VALUE -> new BsonInt32(id.getInt32Value());
      case INT64_VALUE -> new BsonInt64(id.getInt64Value());
      default ->
          throw Status.INVALID_ARGUMENT
              .withDescription("A typed document ID is required")
              .asRuntimeException();
    };
  }

  private static Mutation deletion(BsonValue id) {
    return Mutation.newBuilder()
        .setType(MutationType.MUTATION_TYPE_DELETE)
        .setBson(
            ByteString.copyFrom(
                com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator.encode(
                    new BsonDocument("_id", id))))
        .build();
  }

  CompletableFuture<DeleteByQueryResult> deleteByQuery(
      String principal, DeleteByQueryRequest request) {
    StandaloneAuthInterceptor.requireRole(Role.QUERY);
    condition(request.getConcern());
    LocalAuthoritativeCatalog.validateCollectionId(request.getCollectionId());
    LocalAuthoritativeCatalog.validateRequestId(request.getRequestId());
    int maximum = Math.min(1000, this.runtime.config().documentLimits().maxBatchDocuments());
    if (request.getBatchSize() < 0 || request.getBatchSize() > maximum) {
      throw Status.INVALID_ARGUMENT
          .withDescription("DeleteByQuery batch_size exceeds the configured document limit")
          .asRuntimeException();
    }
    var context = Context.current();
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return context.call(() -> execute(principal, request));
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw Status.UNAVAILABLE
                .withDescription("DeleteByQuery could not complete")
                .withCause(e)
                .asRuntimeException();
          }
        },
        this.executor);
  }

  private DeleteByQueryResult execute(String principal, DeleteByQueryRequest request)
      throws Exception {
    String collection = request.getCollectionId();
    String id = request.getRequestId();
    var lock =
        this.queryLocks[
            Math.floorMod(Objects.hash(principal, collection, id), this.queryLocks.length)];
    while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) {
      checkCancelled();
    }
    try (var pin = this.runtime.store().pinRequest(principal, collection, id)) {
      checkCancelled();
      var manifest = beginQuery(principal, request);
      if (manifest.complete()) {
        if (manifest.statusCode() != 0) {
          throw Status.fromCodeValue(manifest.statusCode())
              .withDescription(manifest.message())
              .asRuntimeException();
        }
        return completed(manifest);
      }
      var progress = DeleteByQueryResult.parseFrom(manifest.response());
      int size =
          request.getBatchSize() == 0
              ? Math.min(100, this.runtime.config().documentLimits().maxBatchDocuments())
              : request.getBatchSize();
      if (!progress.getSelectionComplete()) {
        select(principal, request, progress, size);
      }
      while (true) {
        checkCancelled();
        var pending =
            read(
                tx ->
                    tx.deleteQueryItems(
                        principal, collection, id, DeleteQueryState.INDEXING, size));
        if (!pending.isEmpty()) {
          long barrier = pending.getLast().acceptedSequence();
          // Drain accepted work before taking another batch, including after cancellation.
          try {
            await(this.runtime.ingestion().awaitTargets(manifest.targets(), barrier));
          } catch (io.grpc.StatusRuntimeException failure) {
            if (Context.current().isCancelled()) {
              throw failure;
            }
          }
          refreshPending(principal, collection, id, manifest.targets(), size);
          continue;
        }
        var items =
            read(
                transaction ->
                    transaction.deleteQueryItems(
                        principal, collection, id, DeleteQueryState.SELECTED, size));
        if (items.isEmpty()) {
          break;
        }
        for (var item : items) {
          checkCancelled();
          this.runtime
              .ingestion()
              .coordinate(() -> accept(principal, collection, id, item, manifest.targets()));
        }
        this.runtime.ingestion().wakeConsumers();
      }
      var result = queryResult(principal, collection, id);
      if (result.getFailedCount() > 0 || !result.getFailureMessage().isEmpty()) {
        throw Status.FAILED_PRECONDITION
            .withDescription(result.getFailureMessage())
            .asRuntimeException();
      }
      if (result.getAcceptedSequence() > 0 && !result.getComplete()) {
        await(
            this.runtime
                .ingestion()
                .awaitTargets(
                    manifest.targets(),
                    result.getAcceptedSequence(),
                    condition(request.getConcern())));
      }
      return queryResult(principal, collection, id);
    } finally {
      lock.unlock();
    }
  }

  private RequestRecord beginQuery(String principal, DeleteByQueryRequest request) {
    // describeIndex takes the config-manager lock. Resolve it before acquiring ingestion/store
    // locks, then revalidate the exact generation against the ready targets under coordination.
    var existing =
        read(tx -> tx.request(principal, request.getCollectionId(), request.getRequestId()));
    if (existing.isPresent()) {
      LocalAuthoritativeCatalog.verifyRetry(
          existing.get(), "DeleteByQuery", LocalAuthoritativeCatalog.digest(request.toByteArray()));
      return existing.get();
    }
    var index = this.runtime.describeIndex(request.getIndexId());
    return this.runtime
        .ingestion()
        .coordinate(
            () ->
                transaction(
                    tx -> {
                      var prior =
                          tx.request(principal, request.getCollectionId(), request.getRequestId());
                      if (prior.isPresent()) {
                        LocalAuthoritativeCatalog.verifyRetry(
                            prior.get(),
                            "DeleteByQuery",
                            LocalAuthoritativeCatalog.digest(request.toByteArray()));
                        return prior.get();
                      }
                      var collection =
                          LocalAuthoritativeCatalog.requireCollection(
                              tx, request.getCollectionId());
                      if (!index.getCollectionId().equals(request.getCollectionId())) {
                        throw Status.INVALID_ARGUMENT
                            .withDescription("DeleteByQuery index must belong to collection_id")
                            .asRuntimeException();
                      }
                      if (index.getType()
                              != com.xgen.mongot.standalone.grpc.IndexType.INDEX_TYPE_SEARCH
                          || !index.hasLive()
                          || index.getLive().getGenerationId().isEmpty()
                          || index.getLifecycleState().equals("DROPPED")) {
                        throw Status.FAILED_PRECONDITION
                            .withDescription("DeleteByQuery requires a live search index")
                            .asRuntimeException();
                      }
                      String generation = index.getLive().getGenerationId();
                      if (!request.getGenerationId().isEmpty()
                          && !request.getGenerationId().equals(generation)) {
                        throw Status.FAILED_PRECONDITION
                            .withDescription("Requested generation is no longer live")
                            .asRuntimeException();
                      }
                      var targets = this.runtime.ingestion().captureTargets(collection.id());
                      if (!targets.contains(generation)) {
                        throw Status.FAILED_PRECONDITION
                            .withDescription(
                                "DeleteByQuery requires a ready search index generation")
                            .asRuntimeException();
                      }
                      this.runtime.checkWriteAdmission(request.getSerializedSize());
                      var result =
                          DeleteByQueryResult.newBuilder()
                              .setCollectionId(collection.id())
                              .setRequestId(request.getRequestId())
                              .setIndexId(index.getIndexId())
                              .setGenerationId(generation)
                              .setSnapshotSequence(collection.sequence())
                              .build();
                      var record =
                          new RequestRecord(
                              principal,
                              collection.id(),
                              request.getRequestId(),
                              "DeleteByQuery",
                              LocalAuthoritativeCatalog.digest(request.toByteArray()),
                              request.toByteArray(),
                              targets,
                              false,
                              result.toByteArray(),
                              0,
                              "",
                              System.currentTimeMillis());
                      tx.createRequest(record);
                      return record;
                    }));
  }

  private void select(
      String principal, DeleteByQueryRequest request, DeleteByQueryResult progress, int size)
      throws Exception {
    String collection = request.getCollectionId();
    String id = request.getRequestId();
    // On interrupted selection no source deletion has occurred. Re-select at the original
    // cutoff/generation; never expand the operation to writes accepted after its initial call.
    transaction(
        tx -> {
          tx.clearDeleteQuerySelection(principal, collection, id);
          return true;
        });
    var expression =
        StandaloneBson.document(request.getQueryBson().toByteArray(), 1024 * 1024, 100);
    if (expression.containsKey("returnScope") || expression.containsKey("returnStoredSource")) {
      throw Status.INVALID_ARGUMENT
          .withDescription(
              "DeleteByQuery accepts root-document search expressions without"
                  + " returnScope/returnStoredSource")
          .asRuntimeException();
    }
    await(
        this.runtime
            .ingestion()
            .await(
                request.getIndexId(),
                progress.getGenerationId(),
                progress.getSnapshotSequence(),
                SequenceCondition.SEQUENCE_CONDITION_VISIBLE));
    @Var long cursor = 0;
    try {
      @Var
      QueryResponse page =
          this.queries.query(
              QueryRequest.newBuilder()
                  .setIndexId(request.getIndexId())
                  .setGenerationId(progress.getGenerationId())
                  .setBatchSize(size)
                  .setQueryBson(request.getQueryBson())
                  .build(),
              false);
      cursor = page.getCursorId();
      @Var int ordinal = 0;
      while (true) {
        checkCancelled();
        var rows = new RawBsonDocument(page.getBatchBson().toByteArray()).getArray("results");
        var ids = new ArrayList<byte[]>(rows.size());
        for (var row : rows) {
          var result = row.asDocument();
          if (!result.containsKey("_id")) {
            throw Status.FAILED_PRECONDITION
                .withDescription("DeleteByQuery requires root document IDs in search results")
                .asRuntimeException();
          }
          var mutation = deletion(result.get("_id"));
          ids.add(
              StandaloneBson.mutation(
                      mutation,
                      this.runtime.config().documentLimits().maxDocumentBytes(),
                      this.runtime.config().documentLimits().maxDepth())
                  .encodedId());
        }
        this.runtime.checkWriteAdmission((long) page.getBatchBson().size() + 32768);
        int start = ordinal;
        ordinal =
            transaction(
                tx -> {
                  @Var int next = start;
                  for (byte[] encodedId : ids) {
                    var source = tx.documentSequence(collection, encodedId);
                    var state =
                        source.isEmpty()
                            ? DeleteQueryState.MISSING
                            : source.getAsLong() > progress.getSnapshotSequence()
                                ? DeleteQueryState.CONFLICT
                                : DeleteQueryState.SELECTED;
                    if (tx.addDeleteQueryItem(
                        principal, collection, id, next, encodedId, source.orElse(0), state)) {
                      next++;
                    }
                  }
                  tx.updateRequestProgress(
                      principal,
                      collection,
                      id,
                      progress.toBuilder().setMatchedCount(next).build().toByteArray());
                  return next;
                });
        if (cursor == 0) {
          break;
        }
        page =
            this.queries.nextBatch(
                GetMoreRequest.newBuilder().setCursorId(cursor).setBatchSize(size).build());
        cursor = page.getCursorId();
      }
      int count = ordinal;
      transaction(
          tx -> {
            tx.updateRequestProgress(
                principal,
                collection,
                id,
                progress.toBuilder()
                    .setMatchedCount(count)
                    .setSelectionComplete(true)
                    .build()
                    .toByteArray());
            return true;
          });
    } finally {
      if (cursor != 0) {
        this.queries.discardCursor(cursor);
      }
    }
  }

  private long accept(
      String principal, String collection, String id, DeleteQueryItem item, List<String> targets) {
    @Var Status failure = null;
    try {
      this.runtime.ingestion().validateTargets(targets);
    } catch (io.grpc.StatusRuntimeException e) {
      if (e.getStatus().getCode() != Status.Code.FAILED_PRECONDITION) {
        throw e;
      }
      failure = e.getStatus();
    }
    Status rejected = failure;
    this.runtime.checkWriteAdmission(item.encodedId().length + 32768L);
    return transaction(
        tx -> {
          if (rejected != null) {
            tx.updateDeleteQueryItem(
                principal,
                collection,
                id,
                item.ordinal(),
                DeleteQueryState.SELECTED,
                DeleteQueryState.FAILED,
                0,
                rejected.getCode().value(),
                Objects.requireNonNullElse(rejected.getDescription(), "Generation retired"));
            return 0L;
          }
          var current = tx.documentSequence(collection, item.encodedId());
          if (current.isEmpty() || current.getAsLong() != item.sourceSequence()) {
            tx.updateDeleteQueryItem(
                principal,
                collection,
                id,
                item.ordinal(),
                DeleteQueryState.SELECTED,
                current.isEmpty() ? DeleteQueryState.MISSING : DeleteQueryState.CONFLICT,
                0,
                0,
                "");
            return 0L;
          }
          long sequence = tx.appendMutation(collection, item.encodedId(), item.encodedId(), true);
          if (!tx.updateDeleteQueryItem(
              principal,
              collection,
              id,
              item.ordinal(),
              DeleteQueryState.SELECTED,
              DeleteQueryState.INDEXING,
              sequence,
              0,
              "")) {
            throw new IllegalStateException("DeleteByQuery item was accepted more than once");
          }
          return sequence;
        });
  }

  private void refreshPending(
      String principal, String collection, String id, List<String> targets, int size) {
    var items =
        read(tx -> tx.deleteQueryItems(principal, collection, id, DeleteQueryState.INDEXING, size));
    for (var item : items) {
      this.runtime
          .ingestion()
          .targetOutcome(targets, item.acceptedSequence())
          .ifPresent(
              status ->
                  transaction(
                      tx -> {
                        tx.updateDeleteQueryItem(
                            principal,
                            collection,
                            id,
                            item.ordinal(),
                            DeleteQueryState.INDEXING,
                            status.isOk() ? DeleteQueryState.DELETED : DeleteQueryState.FAILED,
                            item.acceptedSequence(),
                            status.getCode().value(),
                            Objects.requireNonNullElse(status.getDescription(), ""));
                        return true;
                      }));
    }
  }

  DeleteByQueryResult queryResult(String principal, String collection, String id) {
    LocalAuthoritativeCatalog.validateCollectionId(collection);
    LocalAuthoritativeCatalog.validateRequestId(id);
    try (var pin = this.runtime.store().pinRequest(principal, collection, id)) {
      var manifest = record(principal, collection, id);
      if (!manifest.operation().equals("DeleteByQuery")) {
        throw Status.FAILED_PRECONDITION
            .withDescription("Request is not a DeleteByQuery command")
            .asRuntimeException();
      }
      if (manifest.complete()) {
        return completed(manifest);
      }
      refreshPending(principal, collection, id, manifest.targets(), 1000);
      var selectionWasComplete = completed(manifest).getSelectionComplete();
      var counts = read(tx -> tx.deleteQueryCounts(principal, collection, id));
      var request = DeleteByQueryRequest.parseFrom(manifest.payload());
      var outcome =
          counts.acceptedSequence() == 0
              ? Optional.of(Status.OK)
              : this.runtime
                  .ingestion()
                  .targetOutcome(
                      manifest.targets(),
                      counts.acceptedSequence(),
                      condition(request.getConcern()));
      return transaction(
          tx -> {
            var current = tx.request(principal, collection, id).orElseThrow();
            if (current.complete()) {
              return completed(current);
            }
            var latest = tx.deleteQueryCounts(principal, collection, id);
            var result =
                completed(current).toBuilder()
                    .clearFailureMessage()
                    .setMatchedCount(latest.matched())
                    .setDeletedCount(latest.deleted())
                    .setConflictCount(latest.conflicts())
                    .setMissingCount(latest.missing())
                    .setFailedCount(latest.failed())
                    .setAcceptedSequence(latest.acceptedSequence());
            boolean terminal =
                selectionWasComplete
                    && result.getSelectionComplete()
                    && latest.acceptedSequence() == counts.acceptedSequence()
                    && latest.selected() == 0
                    && latest.indexing() == 0
                    && (latest.failed() > 0 || outcome.isPresent());
            if (latest.failed() > 0) {
              result.setFailureMessage(tx.deleteQueryFailure(principal, collection, id));
            }
            if (outcome.isPresent() && !outcome.get().isOk()) {
              result.setFailureMessage(
                  Objects.requireNonNullElse(
                      outcome.get().getDescription(), "Generation unavailable"));
            }
            result.setComplete(terminal);
            var response = result.build();
            if (terminal) {
              tx.finishRequest(
                  principal,
                  collection,
                  id,
                  response.toByteArray(),
                  response.getFailureMessage().isEmpty()
                      ? 0
                      : Status.Code.FAILED_PRECONDITION.value(),
                  response.getFailureMessage());
            } else {
              tx.updateRequestProgress(principal, collection, id, response.toByteArray());
            }
            return response;
          });
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw Status.DATA_LOSS
          .withDescription("Unreadable DeleteByQuery request")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private static DeleteByQueryResult completed(RequestRecord manifest) {
    try {
      return DeleteByQueryResult.parseFrom(manifest.response());
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw Status.DATA_LOSS
          .withDescription("Unreadable DeleteByQuery result")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private RequestRecord record(String principal, String collection, String id) {
    return read(tx -> tx.request(principal, collection, id))
        .orElseThrow(
            () ->
                Status.NOT_FOUND
                    .withDescription("Request does not exist for this principal")
                    .asRuntimeException());
  }

  private <T> T read(StandaloneStore.Work<T> work) {
    try {
      return this.runtime.store().read(work);
    } catch (SQLException e) {
      throw Status.UNAVAILABLE
          .withDescription("Cannot read document command")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private <T> T transaction(StandaloneStore.Work<T> work) {
    try {
      return this.runtime.store().transaction(work);
    } catch (SQLException e) {
      throw Status.UNAVAILABLE
          .withDescription("Cannot persist document command")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private static void checkCancelled() {
    if (Context.current().isCancelled()) {
      throw Status.CANCELLED
          .withDescription("Document command cancelled; retry the same request to resume")
          .asRuntimeException();
    }
  }

  private static <T> T await(CompletableFuture<T> future) {
    try {
      while (true) {
        checkCancelled();
        try {
          return future.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
          /* cancellation is checked between bounded waits */
        }
      }
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException failure) {
        throw failure;
      }
      throw Status.UNAVAILABLE.withCause(e.getCause()).asRuntimeException();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Status.CANCELLED.withDescription("Document command interrupted").asRuntimeException();
    } finally {
      if (Context.current().isCancelled()) {
        future.cancel(false);
      }
    }
  }

  @Override
  public void close() throws InterruptedException {
    this.executor.shutdownNow();
    if (!this.executor.awaitTermination(30, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Document commands did not stop");
    }
  }
}
