package com.xgen.mongot.standalone.runtime;

import com.google.errorprone.annotations.Var;
import com.google.protobuf.CodedOutputStream;
import com.xgen.mongot.catalog.IndexCatalog;
import com.xgen.mongot.index.IndexGeneration;
import com.xgen.mongot.index.ReaderClosedException;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.index.statistics.IndexStatistics;
import com.xgen.mongot.index.statistics.LuceneStatisticsCollector;
import com.xgen.mongot.index.statistics.LuceneStatisticsSnapshot;
import com.xgen.mongot.index.statistics.StatisticsLimitException;
import com.xgen.mongot.standalone.catalog.LocalIndexCatalog;
import com.xgen.mongot.standalone.grpc.GetIndexFieldStatsRequest;
import com.xgen.mongot.standalone.grpc.GetIndexStatsRequest;
import com.xgen.mongot.standalone.grpc.IndexFieldStats;
import com.xgen.mongot.standalone.grpc.IndexFieldStatsResponse;
import com.xgen.mongot.standalone.grpc.IndexStats;
import com.xgen.mongot.standalone.grpc.StatsCollectionState;
import com.xgen.mongot.standalone.ingestion.ApiIngestionManager;
import com.xgen.mongot.standalone.metrics.StandaloneMetrics;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.Nullable;

/** Bounded, generation-fenced diagnostics. Cached pages own values, never Lucene references. */
public final class StandaloneIndexStatistics implements AutoCloseable {
  private static final long SUMMARY_TTL_NANOS = Duration.ofSeconds(5).toNanos();
  private static final long FIELD_TTL_NANOS = Duration.ofSeconds(60).toNanos();
  private static final long SNAPSHOT_TTL_NANOS = Duration.ofMinutes(2).toNanos();
  private static final long MAX_SNAPSHOT_BYTES = 16L * 1024 * 1024;
  private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;
  private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  private static final int MAX_TOKENS = 4096;
  private final LocalIndexCatalog catalog;
  private final IndexCatalog indexes;
  private final ApiIngestionManager ingestion;
  private final StandaloneMetrics metrics;
  private final long collectionBudgetNanos;
  private final LongSupplier ticker;
  private final Collector collector;
  private final ThreadPoolExecutor workers;
  private final ScheduledThreadPoolExecutor cleaner;
  private final Map<String, Slot> slots = new HashMap<>();
  private final LinkedHashMap<String, Retained> snapshots = new LinkedHashMap<>(16, 0.75f, true);
  private final LinkedHashMap<String, Page> pages = new LinkedHashMap<>();
  private long cachedBytes;
  private volatile boolean closed;

  @FunctionalInterface
  interface Collector {
    IndexStatistics collect(
        LuceneStatisticsSnapshot snapshot,
        IndexDefinition definition,
        boolean detailed,
        OptionalLong writerHeap,
        BooleanSupplier cancelled)
        throws IOException;
  }

  private static final class Slot {
    @Nullable private Job job;
    @Nullable private IndexStats summary;
    private long sampledAt;
    private final ArrayDeque<List<String>> pendingFields = new ArrayDeque<>();
    private boolean refreshSummary;
    @Nullable private StatusRuntimeException fieldFailure;
    private List<String> failedPaths = List.of();
    private long failedAt;
  }

  private record Retained(
      IndexFieldStatsResponse response,
      List<String> paths,
      long sampledAt,
      long expiresAt,
      long weight) {}

  private record Page(
      String snapshot,
      String index,
      String generation,
      String principal,
      List<String> paths,
      int pageSize,
      int offset,
      long expiresAt) {}

  private record Capture(
      ApiIngestionManager.StatisticsLease lease,
      LuceneStatisticsSnapshot readers,
      OptionalLong writerHeap)
      implements AutoCloseable {
    @Override
    public void close() throws IOException {
      try {
        this.readers.close();
      } finally {
        this.lease.close();
      }
    }
  }

  public StandaloneIndexStatistics(
      LocalIndexCatalog catalog,
      IndexCatalog indexes,
      ApiIngestionManager ingestion,
      Duration collectionBudget,
      StandaloneMetrics metrics) {
    this(catalog, indexes, ingestion, collectionBudget, metrics, System::nanoTime);
  }

  StandaloneIndexStatistics(
      LocalIndexCatalog catalog,
      IndexCatalog indexes,
      ApiIngestionManager ingestion,
      Duration collectionBudget,
      StandaloneMetrics metrics,
      LongSupplier ticker) {
    this(
        catalog,
        indexes,
        ingestion,
        collectionBudget,
        metrics,
        ticker,
        LuceneStatisticsCollector::collect);
  }

  StandaloneIndexStatistics(
      LocalIndexCatalog catalog,
      IndexCatalog indexes,
      ApiIngestionManager ingestion,
      Duration collectionBudget,
      StandaloneMetrics metrics,
      LongSupplier ticker,
      Collector collector) {
    this.catalog = catalog;
    this.indexes = indexes;
    this.ingestion = ingestion;
    this.metrics = metrics;
    this.collectionBudgetNanos = collectionBudget.toNanos();
    this.ticker = ticker;
    this.collector = collector;
    this.workers =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().name("standalone-statistics-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    this.cleaner =
        new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("statistics-cache").factory());
    this.cleaner.scheduleWithFixedDelay(this::expire, 10, 10, TimeUnit.SECONDS);
    this.metrics.gauge("statistics.queued_jobs", this.workers, worker -> worker.getQueue().size());
    this.metrics.gauge("statistics.active_jobs", this.workers, ThreadPoolExecutor::getActiveCount);
    this.metrics.gauge(
        "statistics.cached_result_bytes_estimate", this, StandaloneIndexStatistics::cachedBytes);
  }

  private IndexGeneration resolve(String index, String requestedGeneration) {
    return this.ingestion.coordinate(
        () -> {
          var desired = this.catalog.getIndex(index);
          var generation =
              this.indexes
                  .getIndexById(new ObjectId(index))
                  .filter(value -> this.ingestion.isGenerationReady(value.getGenerationId()))
                  .orElseThrow(
                      () ->
                          Status.UNAVAILABLE
                              .withDescription("Index is not ready")
                              .asRuntimeException());
          if (!requestedGeneration.isEmpty()
              && !generation.getGenerationId().uniqueString().equals(requestedGeneration)) {
            throw Status.FAILED_PRECONDITION
                .withDescription("Requested generation is no longer live")
                .asRuntimeException();
          }
          if (generation.getDefinition().getDefinitionVersion().orElse(1L)
              != desired.getDesiredRevision()) {
            throw Status.UNAVAILABLE
                .withDescription("Index schema is changing")
                .asRuntimeException();
          }
          return generation;
        });
  }

  public CompletableFuture<IndexStats> summary(GetIndexStatsRequest request) {
    return summary(request, this.ticker.getAsLong());
  }

  private CompletableFuture<IndexStats> summary(GetIndexStatsRequest request, long requestedAt) {
    var generation = resolve(request.getIndexId(), request.getGenerationId());
    Job job;
    synchronized (this) {
      checkOpen();
      var slot =
          this.slots.computeIfAbsent(
              generation.getGenerationId().uniqueString(), unused -> new Slot());
      if (!request.getRefresh()
          && slot.summary != null
          && requestedAt - slot.sampledAt < SUMMARY_TTL_NANOS) {
        return CompletableFuture.completedFuture(age(slot.summary));
      }
      job = schedule(generation, slot, false, List.of());
      if (request.getRefresh() && job.capturedAt > 0 && job.capturedAt < requestedAt) {
        slot.refreshSummary = true;
        return job.result
            .handle((result, error) -> null)
            .thenCompose(unused -> summary(request, requestedAt));
      }
      job.waiters.incrementAndGet();
    }
    var result =
        job.result
            .handle(
                (value, error) -> {
                  if (error != null) {
                    if (job.detailed
                        && status(error).getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED) {
                      return summary(request, requestedAt);
                    }
                    return CompletableFuture.<IndexStats>failedFuture(status(error));
                  }
                  return CompletableFuture.completedFuture(age(value));
                })
            .thenCompose(value -> value);
    result.whenComplete(
        (value, error) -> {
          if (job.waiters.decrementAndGet() == 0 && !job.detailed && !job.result.isDone()) {
            synchronized (this) {
              if (job.slot.pendingFields.isEmpty()) {
                cancel(job);
              }
            }
          }
        });
    return result;
  }

  /** Returns an immutable field page or starts a bounded background collection. */
  public IndexFieldStatsResponse fields(GetIndexFieldStatsRequest request, String principal) {
    int size = request.getPageSize() == 0 ? 100 : request.getPageSize();
    if (size < 1 || size > 500 || request.getPathsCount() > 1000) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Page size must be 1..500 and at most 1000 paths may be selected")
          .asRuntimeException();
    }
    var paths = new TreeSet<String>();
    for (String path : request.getPathsList()) {
      if (path.isEmpty() || path.length() > 4096 || path.indexOf('\0') >= 0) {
        throw Status.INVALID_ARGUMENT
            .withDescription("Invalid canonical field path")
            .asRuntimeException();
      }
      paths.add(path);
    }
    List<String> selected = List.copyOf(paths);
    var generation = resolve(request.getIndexId(), request.getGenerationId());
    String id = generation.getGenerationId().uniqueString();
    synchronized (this) {
      checkOpen();
      expire();
      if (!request.getPageToken().isEmpty()) {
        if (request.getRefresh()) {
          throw Status.INVALID_ARGUMENT
              .withDescription("A page token cannot request refresh")
              .asRuntimeException();
        }
        try {
          UUID.fromString(request.getPageToken());
        } catch (IllegalArgumentException e) {
          throw Status.INVALID_ARGUMENT.withDescription("Invalid page token").asRuntimeException();
        }
        var page = this.pages.get(request.getPageToken());
        if (page == null) {
          throw expired();
        }
        if (!page.principal().equals(principal)
            || !page.index().equals(request.getIndexId())
            || !page.paths().equals(selected)
            || page.pageSize() != size) {
          throw Status.INVALID_ARGUMENT
              .withDescription("Page token does not match this request")
              .asRuntimeException();
        }
        if (!page.generation().equals(id)) {
          throw expired();
        }
        var retained = this.snapshots.get(page.snapshot());
        if (retained == null) {
          throw expired();
        }
        return page(retained, principal, selected, size, page.offset(), false);
      }
      var slot = this.slots.computeIfAbsent(id, unused -> new Slot());
      var retained = find(id, selected);
      long now = this.ticker.getAsLong();
      if (slot.fieldFailure != null
          && slot.failedPaths.equals(selected)
          && now - slot.failedAt < TimeUnit.SECONDS.toNanos(1)
          && !request.getRefresh()) {
        throw slot.fieldFailure;
      }
      boolean refresh =
          request.getRefresh() || retained == null || now - retained.sampledAt() >= FIELD_TTL_NANOS;
      if (refresh) {
        var job = schedule(generation, slot, true, selected);
        if (!job.detailed
            || !covers(job.paths, selected)
            || (request.getRefresh() && job.capturedAt > 0 && job.capturedAt < now)) {
          if (!slot.pendingFields.contains(selected)) {
            if (slot.pendingFields.size() >= 16) {
              throw exhausted("Too many pending field selections");
            }
            slot.pendingFields.add(selected);
          }
        }
      }
      return retained == null
          ? IndexFieldStatsResponse.newBuilder()
              .setState(StatsCollectionState.STATS_COLLECTION_STATE_COLLECTING)
              .setRetryAfterMillis(200)
              .setRefreshInProgress(true)
              .build()
          : page(retained, principal, selected, size, 0, slot.job != null);
    }
  }

  private static boolean covers(List<String> measured, List<String> requested) {
    return measured.isEmpty() || (!requested.isEmpty() && measured.containsAll(requested));
  }

  private @Nullable Retained find(String generation, List<String> paths) {
    var found =
        this.snapshots.values().stream()
            .filter(
                value ->
                    value.response().getIndexStats().getGenerationId().equals(generation)
                        && covers(value.paths(), paths))
            .max(java.util.Comparator.comparingLong(Retained::sampledAt))
            .orElse(null);
    return found == null
        ? null
        : this.snapshots.get(found.response().getIndexStats().getSnapshotId());
  }

  private IndexFieldStatsResponse page(
      Retained retained,
      String principal,
      List<String> paths,
      int size,
      int offset,
      boolean refreshing) {
    var full = retained.response();
    var selected =
        paths.isEmpty()
            ? full.getFieldsList()
            : full.getFieldsList().stream()
                .filter(field -> java.util.Collections.binarySearch(paths, field.getPath()) >= 0)
                .toList();
    if (offset < 0 || offset > selected.size()) {
      throw expired();
    }
    var response =
        full.toBuilder()
            .clearFields()
            .setIndexStats(age(full.getIndexStats()))
            .setRefreshInProgress(refreshing);
    @Var int bytes = response.build().getSerializedSize() + 512;
    @Var int end = offset;
    while (end < selected.size() && end - offset < size) {
      int next =
          CodedOutputStream.computeMessageSize(
              IndexFieldStatsResponse.FIELDS_FIELD_NUMBER, selected.get(end));
      if (bytes + next > MAX_RESPONSE_BYTES) {
        break;
      }
      response.addFields(selected.get(end));
      bytes += next;
      end++;
    }
    if (bytes > MAX_RESPONSE_BYTES || (end == offset && end < selected.size())) {
      throw exhausted("Statistics metadata exceeds the response limit; narrow field paths");
    }
    if (end < selected.size()) {
      var binding =
          new Page(
              full.getIndexStats().getSnapshotId(),
              full.getIndexStats().getIndexId(),
              full.getIndexStats().getGenerationId(),
              principal,
              paths,
              size,
              end,
              retained.expiresAt());
      String token =
          this.pages.entrySet().stream()
              .filter(entry -> entry.getValue().equals(binding))
              .map(Map.Entry::getKey)
              .findFirst()
              .orElseGet(
                  () -> {
                    while (this.pages.size() >= MAX_TOKENS) {
                      this.pages.pollFirstEntry();
                    }
                    String value = UUID.randomUUID().toString();
                    this.pages.put(value, binding);
                    return value;
                  });
      response.setNextPageToken(token);
    }
    return response.build();
  }

  private synchronized Job schedule(
      IndexGeneration generation, Slot slot, boolean detailed, List<String> paths) {
    if (slot.job != null) {
      return slot.job;
    }
    var job = new Job(generation, slot, detailed, paths);
    slot.job = job;
    try {
      this.workers.execute(job);
    } catch (RejectedExecutionException e) {
      slot.job = null;
      this.metrics.count("statistics.rejected", 1);
      throw exhausted("Statistics diagnostic queue is full");
    }
    return job;
  }

  private final class Job implements Runnable {
    private final IndexGeneration generation;
    private final Slot slot;
    private final boolean detailed;
    private final List<String> paths;
    private final CompletableFuture<IndexStats> result = new CompletableFuture<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger waiters = new AtomicInteger();
    private final long deadline;
    private volatile long capturedAt;
    private long capturedAtMillis;

    private Job(IndexGeneration generation, Slot slot, boolean detailed, List<String> paths) {
      this.generation = generation;
      this.slot = slot;
      this.detailed = detailed;
      this.paths = paths;
      this.deadline =
          StandaloneIndexStatistics.this.ticker.getAsLong()
              + StandaloneIndexStatistics.this.collectionBudgetNanos;
    }

    private boolean stopped() {
      return StandaloneIndexStatistics.this.closed
          || this.cancelled.get()
          || StandaloneIndexStatistics.this.ticker.getAsLong() - this.deadline >= 0
          || !StandaloneIndexStatistics.this.ingestion.isGenerationReady(
              this.generation.getGenerationId());
    }

    private Capture capture() {
      return StandaloneIndexStatistics.this.ingestion.coordinate(
          () -> {
            long barrierStarted = System.nanoTime();
            try {
              if (stopped()) {
                throw new CancellationException();
              }
              resolve(
                  this.generation.getGenerationId().indexId.toHexString(),
                  this.generation.getGenerationId().uniqueString());
              var lease =
                  StandaloneIndexStatistics.this.ingestion.acquireStatisticsLease(
                      this.generation.getGenerationId());
              try {
                var index = lease.index();
                index.getReader().refresh();
                var readers = index.getReader().acquireStatisticsSnapshot();
                try {
                  var writerHeap = index.getWriter().getBufferHeapBytes();
                  this.capturedAt = StandaloneIndexStatistics.this.ticker.getAsLong();
                  this.capturedAtMillis = System.currentTimeMillis();
                  return new Capture(lease, readers, writerHeap);
                } catch (RuntimeException | Error | com.xgen.mongot.index.WriterClosedException e) {
                  try {
                    readers.close();
                  } catch (IOException close) {
                    e.addSuppressed(close);
                  }
                  throw e;
                }
              } catch (IOException
                  | ReaderClosedException
                  | com.xgen.mongot.index.WriterClosedException e) {
                lease.close();
                throw Status.UNAVAILABLE
                    .withDescription("Cannot pin statistics readers")
                    .withCause(e)
                    .asRuntimeException();
              } catch (RuntimeException | Error e) {
                lease.close();
                throw e;
              }
            } finally {
              StandaloneIndexStatistics.this.metrics.elapsed(
                  "statistics.barrier.duration", barrierStarted);
            }
          });
    }

    @Override
    public void run() {
      @Var IndexStats summary = null;
      @Var IndexFieldStatsResponse fields = null;
      @Var Throwable failure = null;
      long started = System.nanoTime();
      long allocatedBefore = threadAllocatedBytes();
      try (var capture = capture()) {
        var statistics =
            StandaloneIndexStatistics.this.collector.collect(
                capture.readers(),
                this.generation.getDefinition(),
                this.detailed,
                capture.writerHeap(),
                this::stopped);
        summary =
            StatisticsProtoMapper.summary(
                    statistics,
                    this.generation.getGenerationId().indexId.toHexString(),
                    this.generation.getGenerationId().uniqueString(),
                    this.generation.getDefinition().getDefinitionVersion().orElse(1L),
                    UUID.randomUUID().toString())
                .toBuilder()
                .setSampledAtUnixMillis(this.capturedAtMillis)
                .build();
        if (this.detailed) {
          fields = StatisticsProtoMapper.fields(statistics, summary, this.paths);
          if (weight(fields) > MAX_SNAPSHOT_BYTES) {
            throw new StatisticsLimitException(
                "Field result exceeds cache budget; narrow field paths");
          }
        }
        if (stopped()) {
          throw new CancellationException();
        }
      } catch (Throwable e) {
        failure =
            e instanceof CancellationException
                    && StandaloneIndexStatistics.this.ticker.getAsLong() - this.deadline >= 0
                ? Status.DEADLINE_EXCEEDED
                    .withDescription("Statistics collection budget exceeded")
                    .asRuntimeException()
                : e;
      }
      synchronized (StandaloneIndexStatistics.this) {
        this.slot.job = null;
        if (!this.cancelled.get()
            && !StandaloneIndexStatistics.this.closed
            && StandaloneIndexStatistics.this.ingestion.isGenerationReady(
                this.generation.getGenerationId())) {
          if (summary != null) {
            this.slot.summary = summary;
            this.slot.sampledAt = this.capturedAt;
          }
          if (fields != null && failure == null) {
            retain(fields, this.paths, this.capturedAt);
            this.slot.fieldFailure = null;
          } else if (this.detailed && failure != null) {
            this.slot.fieldFailure = status(failure);
            this.slot.failedPaths = this.paths;
            this.slot.failedAt = StandaloneIndexStatistics.this.ticker.getAsLong();
          }
          if (!this.slot.pendingFields.isEmpty()) {
            this.slot.refreshSummary = false;
            try {
              schedule(this.generation, this.slot, true, this.slot.pendingFields.removeFirst());
            } catch (StatusRuntimeException e) {
              this.slot.fieldFailure = e;
            }
          } else if (this.slot.refreshSummary) {
            this.slot.refreshSummary = false;
            try {
              schedule(this.generation, this.slot, false, List.of());
            } catch (StatusRuntimeException e) {
              /* The waiting RPC retries through normal admission. */
            }
          }
        }
      }
      StandaloneIndexStatistics.this.metrics.elapsed(
          "statistics.duration", started, "kind", this.detailed ? "fields" : "summary");
      long allocatedAfter = threadAllocatedBytes();
      if (allocatedBefore >= 0 && allocatedAfter >= allocatedBefore) {
        StandaloneIndexStatistics.this.metrics.count(
            "statistics.allocated_bytes",
            allocatedAfter - allocatedBefore,
            "kind",
            this.detailed ? "fields" : "summary");
      }
      StandaloneIndexStatistics.this.metrics.count(
          "statistics.completed",
          1,
          "kind",
          this.detailed ? "fields" : "summary",
          "status",
          failure == null ? "OK" : status(failure).getStatus().getCode().name());
      if (summary != null
          && !this.cancelled.get()
          && (failure == null || (this.detailed && failure instanceof StatisticsLimitException))) {
        this.result.complete(summary);
      } else {
        this.result.completeExceptionally(
            status(failure == null ? new CancellationException() : failure));
      }
    }
  }

  private static long threadAllocatedBytes() {
    var bean = ManagementFactory.getThreadMXBean();
    return bean instanceof com.sun.management.ThreadMXBean accounting
            && accounting.isThreadAllocatedMemorySupported()
            && accounting.isThreadAllocatedMemoryEnabled()
        ? accounting.getThreadAllocatedBytes(Thread.currentThread().threadId())
        : -1;
  }

  private static long weight(IndexFieldStatsResponse response) {
    return 4L * response.getSerializedSize()
        + 512L * response.getFieldsCount()
        + 256L
            * response.getFieldsList().stream()
                .mapToLong(IndexFieldStats::getRepresentationsCount)
                .sum();
  }

  private void retain(IndexFieldStatsResponse response, List<String> paths, long sampledAt) {
    long weight = weight(response);
    while (!this.snapshots.isEmpty() && this.cachedBytes + weight > MAX_CACHE_BYTES) {
      var removed = this.snapshots.pollFirstEntry();
      this.cachedBytes -= removed.getValue().weight();
    }
    this.snapshots.put(
        response.getIndexStats().getSnapshotId(),
        new Retained(
            response, paths, sampledAt, this.ticker.getAsLong() + SNAPSHOT_TTL_NANOS, weight));
    this.cachedBytes += weight;
  }

  private synchronized long cachedBytes() {
    return this.cachedBytes;
  }

  private synchronized void expire() {
    long now = this.ticker.getAsLong();
    var iterator = this.snapshots.entrySet().iterator();
    while (iterator.hasNext()) {
      var item = iterator.next();
      if (now - item.getValue().expiresAt() >= 0) {
        this.cachedBytes -= item.getValue().weight();
        iterator.remove();
      }
    }
    this.pages
        .values()
        .removeIf(
            page -> now - page.expiresAt() >= 0 || !this.snapshots.containsKey(page.snapshot()));
    this.slots
        .values()
        .removeIf(slot -> slot.job == null && now - slot.sampledAt >= SNAPSHOT_TTL_NANOS);
  }

  /** Cancels work and releases cached results for a retired or replaced index. */
  public synchronized void invalidate(String index) {
    this.slots
        .entrySet()
        .removeIf(
            entry -> {
              if (entry.getKey().startsWith(index + "-")) {
                if (entry.getValue().job != null) {
                  cancel(entry.getValue().job);
                }
                return true;
              }
              return false;
            });
    var iterator = this.snapshots.values().iterator();
    while (iterator.hasNext()) {
      var value = iterator.next();
      if (value.response().getIndexStats().getIndexId().equals(index)) {
        this.cachedBytes -= value.weight();
        iterator.remove();
      }
    }
    this.pages.values().removeIf(page -> page.index().equals(index));
  }

  private void cancel(Job job) {
    job.cancelled.set(true);
    if (this.workers.remove(job)) {
      job.slot.job = null;
      job.result.completeExceptionally(Status.CANCELLED.asRuntimeException());
    }
  }

  private IndexStats age(IndexStats value) {
    return value.toBuilder()
        .setAgeMillis(Math.max(0, System.currentTimeMillis() - value.getSampledAtUnixMillis()))
        .build();
  }

  private void checkOpen() {
    if (this.closed) {
      throw Status.UNAVAILABLE
          .withDescription("Statistics service is shutting down")
          .asRuntimeException();
    }
  }

  private static StatusRuntimeException expired() {
    return Status.FAILED_PRECONDITION
        .withDescription("Statistics snapshot expired or its generation changed")
        .asRuntimeException();
  }

  private static StatusRuntimeException exhausted(String message) {
    return Status.RESOURCE_EXHAUSTED.withDescription(message).asRuntimeException();
  }

  private static Throwable unwrap(Throwable error) {
    return error instanceof java.util.concurrent.CompletionException && error.getCause() != null
        ? error.getCause()
        : error;
  }

  private static StatusRuntimeException status(Throwable error) {
    Throwable cause = unwrap(error);
    if (cause instanceof StatusRuntimeException status) {
      return status;
    }
    if (cause instanceof StatisticsLimitException) {
      return exhausted(
          java.util.Objects.requireNonNullElse(cause.getMessage(), "Statistics budget exceeded"));
    }
    if (cause instanceof CancellationException || cause instanceof InterruptedException) {
      return Status.CANCELLED.asRuntimeException();
    }
    return Status.UNAVAILABLE
        .withDescription("Statistics collection failed")
        .withCause(cause)
        .asRuntimeException();
  }

  @Override
  public void close() throws InterruptedException {
    synchronized (this) {
      this.closed = true;
      for (var slot : this.slots.values()) {
        if (slot.job != null) {
          cancel(slot.job);
        }
      }
      this.snapshots.clear();
      this.pages.clear();
      this.cachedBytes = 0;
    }
    this.cleaner.shutdownNow();
    this.workers.shutdownNow();
    if (!this.workers.awaitTermination(30, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Statistics collectors did not drain");
    }
    this.cleaner.awaitTermination(10, TimeUnit.SECONDS);
  }
}
