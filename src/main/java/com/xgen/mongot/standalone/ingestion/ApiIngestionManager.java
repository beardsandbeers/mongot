package com.xgen.mongot.standalone.ingestion;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.catalog.IndexCatalog;
import com.xgen.mongot.catalog.InitializedIndexCatalog;
import com.xgen.mongot.index.IndexGeneration;
import com.xgen.mongot.index.version.GenerationId;
import com.xgen.mongot.replication.ReplicationManager;
import com.xgen.mongot.standalone.grpc.IndexWatermarks;
import com.xgen.mongot.standalone.grpc.SequenceCondition;
import com.xgen.mongot.standalone.metrics.StandaloneMetrics;
import com.xgen.mongot.standalone.store.StandaloneStore;
import com.xgen.mongot.util.mongodb.SyncSourceConfig;
import io.grpc.Status;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bson.types.ObjectId;

/**
 * API ingestion behind the existing lifecycle interface. "Replication supported" means that local
 * ingestion is active; no MongoDB source, metadata parser, client or disk-restart loop is used.
 *
 * <p>The manager monitor orders admission, durable catalog changes, target capture and retirement.
 * Callers release it before waiting for any apply/commit/visibility future.
 */
public final class ApiIngestionManager implements ReplicationManager {
  private final StandaloneStore store;
  private final IndexCatalog liveIndexes;
  private final InitializedIndexCatalog initializedIndexes;
  private final Duration commitInterval;
  private final Duration refreshInterval;
  private final java.util.function.LongConsumer beforeApply;
  private final StandaloneMetrics metrics;
  private final Runnable beforeSnapshot;
  private final java.util.concurrent.Semaphore recoverySlots =
      new java.util.concurrent.Semaphore(2, true);
  private final Map<String, GenerationConsumer> consumers = new HashMap<>();
  private final Map<String, CompletableFuture<GenerationConsumer>> initializing = new HashMap<>();
  private boolean stopping;

  public ApiIngestionManager(
      StandaloneStore store,
      IndexCatalog liveIndexes,
      InitializedIndexCatalog initializedIndexes,
      Duration commitInterval,
      Duration refreshInterval) {
    this(store, liveIndexes, initializedIndexes, commitInterval, refreshInterval, unused -> {});
  }

  /** Supplies an apply-boundary observer for local latency instrumentation and fault injection. */
  public ApiIngestionManager(
      StandaloneStore store,
      IndexCatalog liveIndexes,
      InitializedIndexCatalog initializedIndexes,
      Duration commitInterval,
      Duration refreshInterval,
      java.util.function.LongConsumer beforeApply) {
    this(
        store,
        liveIndexes,
        initializedIndexes,
        commitInterval,
        refreshInterval,
        beforeApply,
        StandaloneMetrics.disabled());
  }

  /** Shares service instrumentation with every local generation. */
  public ApiIngestionManager(
      StandaloneStore store,
      IndexCatalog liveIndexes,
      InitializedIndexCatalog initializedIndexes,
      Duration commitInterval,
      Duration refreshInterval,
      java.util.function.LongConsumer beforeApply,
      StandaloneMetrics metrics) {
    this(
        store,
        liveIndexes,
        initializedIndexes,
        commitInterval,
        refreshInterval,
        beforeApply,
        metrics,
        () -> {});
  }

  /** Installs an observer after the source snapshot and its retention pin have been acquired. */
  public ApiIngestionManager(
      StandaloneStore store,
      IndexCatalog liveIndexes,
      InitializedIndexCatalog initializedIndexes,
      Duration commitInterval,
      Duration refreshInterval,
      java.util.function.LongConsumer beforeApply,
      StandaloneMetrics metrics,
      Runnable beforeSnapshot) {
    if (commitInterval.isNegative()
        || commitInterval.isZero()
        || refreshInterval.isNegative()
        || refreshInterval.isZero()) {
      throw new IllegalArgumentException("Commit and refresh intervals must be positive");
    }
    this.store = store;
    this.liveIndexes = liveIndexes;
    this.initializedIndexes = initializedIndexes;
    this.commitInterval = commitInterval;
    this.refreshInterval = refreshInterval;
    this.beforeApply = beforeApply;
    this.metrics = metrics;
    this.beforeSnapshot = beforeSnapshot;
  }

  /** A short coordination operation. Never wait for indexing or acquire the config-manager lock. */
  public synchronized <T> T coordinate(Supplier<T> operation) {
    if (this.stopping) {
      throw Status.UNAVAILABLE
          .withDescription("Standalone ingestion is shutting down")
          .asRuntimeException();
    }
    return operation.get();
  }

  @Override
  public synchronized void add(IndexGeneration generation) {
    if (this.stopping) {
      return;
    }
    String id = generation.getGenerationId().uniqueString();
    if (this.consumers.containsKey(id)) {
      return;
    }
    var initialized =
        this.initializedIndexes
            .getIndex(generation.getGenerationId())
            .orElseThrow(
                () -> new IllegalStateException("Lifecycle added an uninitialized generation"));
    var consumer =
        new GenerationConsumer(
            this.store,
            initialized,
            this.commitInterval,
            this.refreshInterval,
            this.beforeApply,
            this.metrics,
            this.beforeSnapshot,
            this.recoverySlots);
    this.consumers.put(id, consumer);
    var registered = this.initializing.remove(id);
    if (registered != null) {
      registered.complete(consumer);
    }
    consumer.start();
  }

  public synchronized boolean isGenerationReady(GenerationId generationId) {
    var consumer = this.consumers.get(generationId.uniqueString());
    return consumer != null && consumer.isReady();
  }

  /** Captures every ready live generation, excluding staged/new builds and durable drop intents. */
  public synchronized List<String> captureTargets(String collectionId) {
    if (this.stopping) {
      throw Status.UNAVAILABLE
          .withDescription("Standalone ingestion is shutting down")
          .asRuntimeException();
    }
    try {
      var desired = this.store.read(transaction -> transaction.indexes(collectionId, false));
      var targets = new ArrayList<String>();
      for (var index : desired) {
        var generation = this.liveIndexes.getIndexById(new ObjectId(index.id()));
        if (generation.isEmpty()) {
          continue;
        }
        String id = generation.get().getGenerationId().uniqueString();
        var consumer = this.consumers.get(id);
        if (consumer != null && consumer.isReady()) {
          targets.add(id);
        } else if ((consumer != null && consumer.hasCompletedBuild())
            || this.store.read(transaction -> transaction.wasGenerationReady(id))) {
          throw Status.UNAVAILABLE
              .withDescription("A live generation cannot accept indexing")
              .asRuntimeException();
        }
      }
      if (targets.isEmpty()) {
        throw Status.FAILED_PRECONDITION
            .withDescription("Collection has no live ready index")
            .asRuntimeException();
      }
      return List.copyOf(targets);
    } catch (SQLException e) {
      throw Status.UNAVAILABLE
          .withDescription("Cannot read ingestion targets")
          .withCause(e)
          .asRuntimeException();
    }
  }

  /**
   * Used under coordinate() immediately before source acceptance, including interrupted retries.
   */
  public synchronized void validateTargets(List<String> targets) {
    if (this.stopping) {
      throw Status.UNAVAILABLE
          .withDescription("Standalone ingestion is shutting down")
          .asRuntimeException();
    }
    for (String id : targets) {
      var consumer = this.consumers.get(id);
      if (consumer == null) {
        boolean live =
            this.liveIndexes.getIndexes().stream()
                .anyMatch(index -> index.getGenerationId().uniqueString().equals(id));
        if (live) {
          throw Status.UNAVAILABLE
              .withDescription("A captured generation is initializing")
              .asRuntimeException();
        }
        throw Status.FAILED_PRECONDITION
            .withDescription("A captured generation was retired")
            .asRuntimeException();
      }
      if (consumer.failure().isPresent()) {
        throw Status.FAILED_PRECONDITION
            .withDescription("A captured generation failed")
            .asRuntimeException();
      }
      if (!consumer.isReady()) {
        throw Status.UNAVAILABLE
            .withDescription("A captured generation is recovering")
            .asRuntimeException();
      }
    }
  }

  public synchronized void wakeConsumers() {
    this.consumers.values().forEach(GenerationConsumer::wake);
  }

  public synchronized CompletableFuture<Void> awaitTargets(List<String> targets, long sequence) {
    var waits = new ArrayList<CompletableFuture<IndexWatermarks>>();
    for (String target : targets) {
      waits.add(awaitConsumer(target, sequence, SequenceCondition.SEQUENCE_CONDITION_APPLIED));
    }
    var barrier = CompletableFuture.allOf(waits.toArray(CompletableFuture<?>[]::new));
    barrier.whenComplete(
        (ignored, error) -> {
          if (barrier.isCancelled()) {
            waits.forEach(wait -> wait.cancel(false));
          }
        });
    return barrier;
  }

  public synchronized CompletableFuture<IndexWatermarks> await(
      String indexId, String generationId, long sequence, SequenceCondition condition) {
    if (!ObjectId.isValid(indexId) || !generationId.startsWith(indexId + "-")) {
      return CompletableFuture.failedFuture(
          Status.INVALID_ARGUMENT
              .withDescription("Generation does not belong to the requested index")
              .asRuntimeException());
    }
    return awaitConsumer(generationId, sequence, condition);
  }

  private CompletableFuture<IndexWatermarks> awaitConsumer(
      String id, long sequence, SequenceCondition condition) {
    var consumer = this.consumers.get(id);
    if (consumer != null) {
      return consumer.await(sequence, condition);
    }
    boolean live =
        this.liveIndexes.getIndexes().stream()
            .anyMatch(index -> index.getGenerationId().uniqueString().equals(id));
    if (!live || this.stopping) {
      return CompletableFuture.failedFuture(
          Status.FAILED_PRECONDITION
              .withDescription("Generation was retired or dropped")
              .asRuntimeException());
    }
    var registration = this.initializing.computeIfAbsent(id, unused -> new CompletableFuture<>());
    var result = new CompletableFuture<IndexWatermarks>();
    registration.whenComplete(
        (registered, error) -> {
          if (result.isCancelled()) {
            return;
          }
          if (error != null) {
            result.completeExceptionally(error);
            return;
          }
          var wait = registered.await(sequence, condition);
          result.whenComplete(
              (ignored, failure) -> {
                if (result.isCancelled()) {
                  wait.cancel(false);
                }
              });
          wait.whenComplete(
              (watermarks, failure) -> {
                if (failure == null) {
                  result.complete(watermarks);
                } else {
                  result.completeExceptionally(failure);
                }
              });
        });
    return result;
  }

  /** Observes completion without changing source state, including after a disconnected caller. */
  public synchronized Optional<Status> targetOutcome(List<String> targets, long sequence) {
    @Var boolean pending = false;
    for (String id : targets) {
      var consumer = this.consumers.get(id);
      if (consumer == null) {
        boolean live =
            this.liveIndexes.getIndexes().stream()
                .anyMatch(index -> index.getGenerationId().uniqueString().equals(id));
        if (live && !this.stopping) {
          pending = true;
          continue;
        }
        return Optional.of(
            Status.FAILED_PRECONDITION.withDescription("A captured generation was retired"));
      }
      if (consumer.hasCompletedBuild() && consumer.watermarks().getAppliedSequence() >= sequence) {
        continue;
      }
      if (consumer.failure().isPresent() || this.stopping) {
        return Optional.of(
            Status.UNAVAILABLE.withDescription("A target generation failed during indexing"));
      }
      pending = true;
    }
    return pending ? Optional.empty() : Optional.of(Status.OK);
  }

  public synchronized Optional<IndexWatermarks> watermarks(GenerationId generationId) {
    return Optional.ofNullable(this.consumers.get(generationId.uniqueString()))
        .map(GenerationConsumer::watermarks);
  }

  public synchronized Optional<String> failure(GenerationId generationId) {
    return Optional.ofNullable(this.consumers.get(generationId.uniqueString()))
        .flatMap(GenerationConsumer::failure);
  }

  @Override
  public synchronized CompletableFuture<Void> dropIndex(GenerationId generationId) {
    var registration = this.initializing.remove(generationId.uniqueString());
    if (registration != null) {
      registration.completeExceptionally(
          Status.FAILED_PRECONDITION
              .withDescription("Generation was dropped")
              .asRuntimeException());
    }
    var consumer = this.consumers.remove(generationId.uniqueString());
    return consumer == null ? CompletableFuture.completedFuture(null) : consumer.stop(false);
  }

  @Override
  public Optional<SyncSourceConfig> getSyncSourceConfig() {
    return Optional.empty();
  }

  @Override
  public synchronized boolean isInitialized() {
    return !this.stopping;
  }

  @Override
  public boolean isReplicationSupported() {
    return true;
  }

  @Override
  public synchronized CompletableFuture<Void> shutdown() {
    this.stopping = true;
    this.initializing
        .values()
        .forEach(
            future ->
                future.completeExceptionally(
                    Status.UNAVAILABLE
                        .withDescription("Standalone ingestion is shutting down")
                        .asRuntimeException()));
    this.initializing.clear();
    var stops = this.consumers.values().stream().map(consumer -> consumer.stop(true)).toList();
    return CompletableFuture.allOf(stops.toArray(CompletableFuture<?>[]::new));
  }
}
