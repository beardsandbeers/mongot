package com.xgen.mongot.standalone.ingestion;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.index.EncodedUserData;
import com.xgen.mongot.index.InitializedIndex;
import com.xgen.mongot.index.lucene.LuceneIndexFactory;
import com.xgen.mongot.index.status.IndexStatus;
import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.grpc.IndexWatermarks;
import com.xgen.mongot.standalone.grpc.SequenceCondition;
import com.xgen.mongot.standalone.metrics.StandaloneMetrics;
import com.xgen.mongot.standalone.store.StandaloneStore;
import io.grpc.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** One ordered apply/commit/refresh lane. Lifecycle owns the index after stop() completes. */
final class GenerationConsumer {
  private record Waiter(
      long sequence, SequenceCondition condition, CompletableFuture<IndexWatermarks> future) {}

  private final StandaloneStore store;
  private final InitializedIndex index;
  private final StandaloneDocumentIndexer indexer;
  private final String collectionId;
  private final String generationId;
  private final String definitionHash;
  private final long commitIntervalNanos;
  private final long refreshIntervalNanos;
  private final java.util.function.LongConsumer beforeApply;
  private final StandaloneMetrics metrics;
  private final Runnable beforeSnapshot;
  private final java.util.concurrent.Semaphore recoverySlots;
  private final Object signal = new Object();
  private final List<Waiter> waiters = new ArrayList<>();
  private final CompletableFuture<Void> stopped = new CompletableFuture<>();

  private volatile boolean ready;
  private volatile boolean completedBuild;
  private volatile boolean stopping;
  private volatile boolean commitOnStop;
  private volatile Optional<String> failure = Optional.empty();
  private volatile long applied;
  private volatile long committed;
  private volatile long visible;
  private volatile long snapshotDocuments;

  GenerationConsumer(
      StandaloneStore store,
      InitializedIndex index,
      Duration commitInterval,
      Duration refreshInterval,
      java.util.function.LongConsumer beforeApply,
      StandaloneMetrics metrics,
      Runnable beforeSnapshot,
      java.util.concurrent.Semaphore recoverySlots) {
    if (!LuceneIndexFactory.isInitializedLuceneIndex(index)) {
      throw new IllegalArgumentException(
          "Standalone requires the audited Lucene index implementation");
    }
    this.store = store;
    this.index = index;
    this.indexer = new StandaloneDocumentIndexer(index.getWriter());
    this.collectionId = index.getDefinition().getCollectionUuid().toString();
    this.generationId = index.getGenerationId().uniqueString();
    this.definitionHash =
        HexFormat.of()
            .formatHex(
                LocalAuthoritativeCatalog.digest(
                    StandaloneDefinitionValidator.normalize(index.getDefinition())));
    this.commitIntervalNanos = commitInterval.toNanos();
    this.refreshIntervalNanos = refreshInterval.toNanos();
    this.beforeApply = beforeApply;
    this.metrics = metrics;
    this.beforeSnapshot = beforeSnapshot;
    this.recoverySlots = recoverySlots;
    index.setStatus(IndexStatus.initialSync("Recovering from the local source store"));
  }

  void start() {
    Thread.ofVirtual().name("standalone-ingestion-" + this.generationId).start(this::run);
  }

  boolean isReady() {
    return this.ready && !this.stopping && this.failure.isEmpty();
  }

  boolean hasCompletedBuild() {
    return this.completedBuild;
  }

  Optional<String> failure() {
    return this.failure;
  }

  IndexWatermarks watermarks() {
    synchronized (this.signal) {
      return IndexWatermarks.newBuilder()
          .setGenerationId(this.generationId)
          .setAppliedSequence(this.applied)
          .setCommittedSequence(this.committed)
          .setVisibleSequence(this.visible)
          .setSnapshotDocuments(this.snapshotDocuments)
          .setSnapshotComplete(this.completedBuild)
          .build();
    }
  }

  CompletableFuture<IndexWatermarks> await(long sequence, SequenceCondition condition) {
    if (sequence < 0
        || condition == SequenceCondition.SEQUENCE_CONDITION_UNSPECIFIED
        || condition == SequenceCondition.UNRECOGNIZED) {
      return CompletableFuture.failedFuture(
          Status.INVALID_ARGUMENT
              .withDescription(
                  "A supported sequence condition and nonnegative position are required")
              .asRuntimeException());
    }
    synchronized (this.signal) {
      if (this.stopping || this.failure.isPresent()) {
        return CompletableFuture.failedFuture(unavailable());
      }
      if (reached(sequence, condition)) {
        return CompletableFuture.completedFuture(watermarks());
      }
      var future = new CompletableFuture<IndexWatermarks>();
      var waiter = new Waiter(sequence, condition, future);
      this.waiters.add(waiter);
      future.whenComplete(
          (ignored, error) -> {
            synchronized (this.signal) {
              this.waiters.remove(waiter);
            }
          });
      this.signal.notifyAll();
      return future;
    }
  }

  void wake() {
    synchronized (this.signal) {
      this.signal.notifyAll();
    }
  }

  CompletableFuture<Void> stop(boolean commit) {
    synchronized (this.signal) {
      this.commitOnStop = commit;
      this.stopping = true;
      this.signal.notifyAll();
    }
    publish();
    return this.stopped.thenRun(
        () -> {
          try {
            this.store.transaction(
                transaction -> {
                  transaction.unpin(this.generationId);
                  return true;
                });
            this.metrics.retireGeneration(this.generationId);
          } catch (java.sql.SQLException e) {
            throw new java.util.concurrent.CompletionException(e);
          }
        });
  }

  private void run() {
    try {
      long recoveryStart = System.nanoTime();
      while (!this.recoverySlots.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        checkRunning();
      }
      try {
        recover();
      } finally {
        this.recoverySlots.release();
      }
      this.metrics.elapsed("recovery", recoveryStart, "generation", this.generationId);
      @Var long lastCommit = System.nanoTime();
      @Var long lastRefresh = lastCommit;
      while (!this.stopping) {
        boolean progressed = replayChunk(Long.MAX_VALUE);
        long now = System.nanoTime();
        if (now - lastCommit >= this.commitIntervalNanos && this.applied > this.committed) {
          commit(true);
          lastCommit = now;
        }
        if (now - lastRefresh >= this.refreshIntervalNanos && this.applied > this.visible) {
          refresh();
          lastRefresh = now;
        }
        if (!progressed) {
          synchronized (this.signal) {
            if (!this.stopping) {
              this.signal.wait(10);
            }
          }
        }
      }
      if (this.commitOnStop && this.ready && this.failure.isEmpty()) {
        commit(true);
      }
    } catch (Exception e) {
      if (!this.stopping) {
        String message =
            "Local ingestion failed after sequence "
                + this.applied
                + " ("
                + e.getClass().getSimpleName()
                + ")";
        this.failure = Optional.of(message);
        this.ready = false;
        this.index.setStatus(IndexStatus.failed(message, IndexStatus.Reason.USER_ERROR));
        this.metrics.count("ingestion.failures", 1);
      }
    } finally {
      publish();
      // Failure retains the pin; stop() releases it only after this lane has finished.
      this.stopped.complete(null);
    }
  }

  private void recover() throws Exception {
    this.store.transaction(
        transaction -> {
          transaction.pin(this.generationId, this.collectionId, 0);
          return true;
        });
    long accepted = acceptedSequence();
    // This writer has just been reopened and has not received any new metadata. In the audited
    // multi-partition implementation it reads partition zero, which commits last.
    EncodedUserData encoded = this.index.getWriter().getCommitUserData();
    @Var boolean rebuild = encoded.equals(EncodedUserData.EMPTY);
    if (!rebuild) {
      var checkpoint = StandaloneIndexCommitUserData.decode(encoded);
      checkpoint.validateIdentity(
          this.store.instanceId(),
          this.collectionId,
          this.generationId,
          this.definitionHash,
          accepted);
      rebuild = !checkpoint.buildComplete();
      this.completedBuild = checkpoint.buildComplete();
      if (!rebuild) {
        synchronized (this.signal) {
          this.applied = checkpoint.lastAppliedSequence();
          this.committed = this.applied;
        }
        rebuild =
            this.store.read(transaction -> transaction.prunedThrough(this.collectionId))
                > this.applied;
      }
    }
    if (rebuild) {
      buildSnapshot();
    }
    @Var long barrier = acceptedSequence();
    while (this.applied < barrier) {
      if (!replayChunk(barrier)) {
        // A retained suffix is missing. Rebuild from authoritative documents, never skip ahead.
        buildSnapshot();
        barrier = acceptedSequence();
      }
    }
    checkRunning();
    commit(true);
    refresh();
    this.store.transaction(
        transaction -> {
          transaction.markGenerationReady(this.generationId, this.collectionId);
          return true;
        });
    this.completedBuild = true;
    this.ready = true;
    this.index.setStatus(IndexStatus.steady());
    publish();
  }

  private void buildSnapshot() throws Exception {
    checkRunning();
    this.snapshotDocuments = 0;
    this.index.getWriter().deleteAll(checkpoint(false, 0).encode());
    try (var snapshot = this.store.snapshot(this.collectionId)) {
      // The zero pin was installed before opening this snapshot. Advancing it to S preserves
      // every later mutation, even while writes and pruning run during the scan.
      this.store.transaction(
          transaction -> {
            transaction.pin(this.generationId, this.collectionId, snapshot.sequence());
            return true;
          });
      this.beforeSnapshot.run();
      snapshot.scan(
          bson -> {
            checkRunning();
            this.indexer.snapshotDocument(bson);
            this.snapshotDocuments++;
          });
      synchronized (this.signal) {
        this.applied = snapshot.sequence();
        this.committed = 0;
        this.visible = 0;
      }
    }
  }

  private boolean replayChunk(long barrier) throws Exception {
    checkRunning();
    var mutations =
        this.store
            .read(
                transaction ->
                    transaction.mutationsAfter(
                        this.collectionId, this.applied, 256, 8 * 1024 * 1024))
            .stream()
            .takeWhile(mutation -> mutation.sequence() <= barrier)
            .toList();
    if (mutations.isEmpty()) {
      return false;
    }
    if (mutations.getFirst().sequence() != this.applied + 1) {
      if (this.ready) {
        throw new IllegalStateException(
            "Live generation requires a source rebuild after a log gap");
      }
      buildSnapshot();
      return true;
    }
    var events = this.indexer.prepare(mutations);
    if (events.size() != mutations.size()) {
      throw new IllegalStateException("Writer changed the replay batch cardinality");
    }
    for (int i = 0; i < events.size(); i++) {
      checkRunning();
      if (mutations.get(i).sequence() != this.applied + 1) {
        throw new IllegalStateException("Noncontiguous local mutation log");
      }
      this.beforeApply.accept(mutations.get(i).sequence());
      checkRunning();
      long applyStart = System.nanoTime();
      this.indexer.apply(events.get(i));
      this.metrics.elapsed("apply", applyStart, "generation", this.generationId);
      this.metrics.count("replay.mutations", 1);
      synchronized (this.signal) {
        this.applied = mutations.get(i).sequence();
      }
      publish();
    }
    return true;
  }

  private long acceptedSequence() throws Exception {
    return this.store
        .read(transaction -> transaction.collection(this.collectionId))
        .orElseThrow(() -> new IllegalStateException("Generation source collection is missing"))
        .sequence();
  }

  private StandaloneIndexCommitUserData checkpoint(boolean buildComplete, long sequence) {
    return new StandaloneIndexCommitUserData(
        this.store.instanceId(),
        this.collectionId,
        this.generationId,
        this.definitionHash,
        buildComplete,
        sequence);
  }

  private void commit(boolean buildComplete) throws Exception {
    long started = System.nanoTime();
    this.index.getWriter().commit(checkpoint(buildComplete, this.applied).encode());
    this.metrics.elapsed("commit", started, "generation", this.generationId);
    this.store.transaction(
        transaction -> {
          transaction.pin(this.generationId, this.collectionId, this.applied);
          return true;
        });
    synchronized (this.signal) {
      this.committed = this.applied;
    }
    publish();
  }

  private void refresh() throws Exception {
    long started = System.nanoTime();
    this.index.getReader().refresh();
    this.metrics.elapsed("refresh", started, "generation", this.generationId);
    synchronized (this.signal) {
      this.visible = this.applied;
    }
    publish();
  }

  private void checkRunning() {
    if (this.stopping) {
      throw new CancellationException("Generation consumer is stopping");
    }
  }

  private boolean reached(long sequence, SequenceCondition condition) {
    return switch (condition) {
      case SEQUENCE_CONDITION_APPLIED -> this.ready && this.applied >= sequence;
      case SEQUENCE_CONDITION_COMMITTED -> this.ready && this.committed >= sequence;
      case SEQUENCE_CONDITION_VISIBLE -> this.ready && this.visible >= sequence;
      default -> false;
    };
  }

  private RuntimeException unavailable() {
    return Status.FAILED_PRECONDITION
        .withDescription(this.failure.orElse("Generation was retired"))
        .asRuntimeException();
  }

  private void publish() {
    List<Waiter> completed;
    IndexWatermarks watermarks;
    synchronized (this.signal) {
      completed =
          this.waiters.stream()
              .filter(
                  waiter ->
                      this.stopping
                          || this.failure.isPresent()
                          || reached(waiter.sequence(), waiter.condition()))
              .toList();
      this.waiters.removeAll(completed);
      watermarks = watermarks();
    }
    for (var waiter : completed) {
      if (this.stopping || this.failure.isPresent()) {
        waiter.future().completeExceptionally(unavailable());
      } else {
        waiter.future().complete(watermarks);
      }
    }
  }
}
