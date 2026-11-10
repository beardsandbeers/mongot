package com.xgen.mongot.standalone.runtime;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.catalog.DefaultIndexCatalog;
import com.xgen.mongot.catalog.InitializedIndexCatalog;
import com.xgen.mongot.config.manager.DefaultConfigManager;
import com.xgen.mongot.config.manager.IncompatibleIndexes;
import com.xgen.mongot.cursor.CursorConfig;
import com.xgen.mongot.cursor.MongotCursorManagerImpl;
import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.featureflag.dynamic.DynamicFeatureFlagRegistry;
import com.xgen.mongot.index.analyzer.AnalyzerRegistry;
import com.xgen.mongot.index.definition.SearchIndexDefinition;
import com.xgen.mongot.index.definition.VectorIndexDefinition;
import com.xgen.mongot.index.lucene.LuceneGlobalSettings;
import com.xgen.mongot.index.lucene.LuceneIndexFactory;
import com.xgen.mongot.index.lucene.config.LuceneConfig;
import com.xgen.mongot.index.lucene.directory.EnvironmentVariantPerfConfig;
import com.xgen.mongot.lifecycle.DefaultLifecycleManager;
import com.xgen.mongot.lifecycle.LifecycleConfig;
import com.xgen.mongot.metrics.MeterAndFtdcRegistry;
import com.xgen.mongot.monitor.ReplicationStateMonitor;
import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.ServiceMetric;
import com.xgen.mongot.standalone.grpc.ServiceStatus;
import com.xgen.mongot.standalone.ingestion.ApiIngestionManager;
import com.xgen.mongot.standalone.ingestion.DurableWriteLedger;
import com.xgen.mongot.standalone.metrics.StandaloneMetrics;
import com.xgen.mongot.standalone.store.StandaloneStore;
import io.grpc.Status;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.Nullable;

/**
 * Standalone composition with local desired state, standard physical lifecycle and API ingestion.
 */
public final class StandaloneRuntime implements Closeable {
  private record BuildObservation(long revision, long startedNanos) {}

  private final java.util.Map<String, BuildObservation> builds = new java.util.HashMap<>();
  private final StandaloneConfig config;
  private final StandaloneStore store;
  private final LocalAuthoritativeCatalog catalog;
  private final DurableWriteLedger ledger;
  private final DefaultIndexCatalog liveIndexes;
  private final MongotCursorManagerImpl cursors;
  private final ApiIngestionManager ingestion;
  private final DefaultConfigManager configManager;
  private final MeterAndFtdcRegistry metrics;
  private final StandaloneMetrics serviceMetrics;
  private final ArrayDeque<AutoCloseable> resources = new ArrayDeque<>();
  private final ScheduledThreadPoolExecutor reconciler;
  private final AtomicBoolean reconcileQueued = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<Optional<String>> reconciliationFailure =
      new AtomicReference<>(Optional.empty());
  private final AtomicReference<Optional<String>> retentionFailure =
      new AtomicReference<>(Optional.empty());

  /** Acquires the data-directory lock before constructing any index or background task. */
  public StandaloneRuntime(StandaloneConfig config) throws Exception {
    this(config, unused -> {});
  }

  /** Allows in-process fault injection at the apply boundary without replacing real persistence. */
  @org.jetbrains.annotations.TestOnly
  public StandaloneRuntime(StandaloneConfig config, java.util.function.LongConsumer beforeApply)
      throws Exception {
    this(config, beforeApply, () -> {});
  }

  /** Installs bounded test observers at apply and pinned-snapshot boundaries. */
  @org.jetbrains.annotations.TestOnly
  public StandaloneRuntime(
      StandaloneConfig config, java.util.function.LongConsumer beforeApply, Runnable beforeSnapshot)
      throws Exception {
    this.config = config;
    this.store = new StandaloneStore(config.dataPath());
    this.resources.addFirst(this.store);
    try {
      this.metrics = MeterAndFtdcRegistry.createWithSimpleRegistries();
      this.serviceMetrics = new StandaloneMetrics(this.metrics.meterRegistry());
      this.resources.addFirst(
          () -> {
            this.metrics.ftdcRegistry().close();
            this.metrics.meterRegistry().close();
          });
      this.catalog = new LocalAuthoritativeCatalog(this.store);
      this.ledger =
          new DurableWriteLedger(this.store, config.documentLimits(), this.serviceMetrics);
      this.liveIndexes = new DefaultIndexCatalog();
      var initialized = new InitializedIndexCatalog();
      var flags = FeatureFlags.communityDefaults();
      var dynamicFlags = DynamicFeatureFlagRegistry.communityDefault();
      var diskMonitor = new StandaloneDiskMonitor(config.dataPath());
      this.resources.addFirst(diskMonitor::stop);
      diskMonitor.start(Duration.ofSeconds(1));
      var luceneConfig = luceneConfig(config.dataPath(), config.refreshInterval());
      LuceneGlobalSettings.apply(luceneConfig);
      var factory =
          LuceneIndexFactory.fromConfig(
              luceneConfig,
              flags,
              dynamicFlags,
              EnvironmentVariantPerfConfig.getDefault(),
              this.metrics,
              Optional.empty(),
              AnalyzerRegistry.factory(),
              diskMonitor);
      this.resources.addFirst(factory);
      this.cursors =
          MongotCursorManagerImpl.fromConfig(
              CursorConfig.getDefault(),
              this.metrics.meterRegistry(),
              this.liveIndexes,
              initialized);
      AutoCloseable cursorOwner = this.cursors::close;
      this.resources.addFirst(cursorOwner);
      this.ingestion =
          new ApiIngestionManager(
              this.store,
              this.liveIndexes,
              initialized,
              config.commitInterval(),
              config.refreshInterval(),
              beforeApply,
              this.serviceMetrics,
              beforeSnapshot);
      var lifecycle =
          new DefaultLifecycleManager(
              unused -> this.ingestion,
              Optional.empty(),
              initialized,
              factory,
              Optional.empty(),
              unused -> Optional.empty(),
              this.metrics.meterRegistry(),
              ReplicationStateMonitor.enabled(),
              LifecycleConfig.getDefault(),
              false,
              this.metrics.threadPoolResourceBindings());
      AutoCloseable lifecycleOwner = () -> lifecycle.shutdown().get(30, TimeUnit.SECONDS);
      this.resources.addFirst(lifecycleOwner);
      var desiredIds =
          this.store.read(transaction -> transaction.allIndexes()).stream()
              .filter(index -> !index.dropped())
              .map(index -> new ObjectId(index.id()))
              .collect(java.util.stream.Collectors.toSet());
      this.configManager =
          DefaultConfigManager.initialize(
              this.liveIndexes,
              Optional.of(desiredIds),
              initialized,
              new IncompatibleIndexes(this.metrics.meterRegistry()),
              this.cursors,
              factory,
              Optional.empty(),
              config.dataPath().resolve("configJournal.json"),
              this.metrics.meterRegistry(),
              lifecycle,
              ReplicationStateMonitor.enabled(),
              flags,
              config.dataPath().resolve("dynamicFeatureFlags.json"),
              dynamicFlags,
              Optional.of(this.ingestion::isGenerationReady));
      // The config manager now owns these three resources, including their drain/close ordering.
      this.resources.remove(factory);
      this.resources.remove(cursorOwner);
      this.resources.remove(lifecycleOwner);
      this.resources.addFirst(this.configManager::close);
      reconcile();
      this.reconciler =
          new ScheduledThreadPoolExecutor(
              1, Thread.ofPlatform().name("standalone-catalog").factory());
      this.reconciler.setRemoveOnCancelPolicy(true);
      this.resources.addFirst(
          () -> {
            this.reconciler.shutdown();
            if (!this.reconciler.awaitTermination(30, TimeUnit.SECONDS)) {
              throw new IOException("Local reconciliation did not drain before shutdown");
            }
          });
      this.reconciler.scheduleWithFixedDelay(
          this::reconcileSafely, 100, 100, TimeUnit.MILLISECONDS);
      this.reconciler.scheduleWithFixedDelay(this::maintainSafely, 1, 1, TimeUnit.SECONDS);
    } catch (Exception | Error e) {
      try {
        closeResources();
      } catch (IOException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }

  public StandaloneConfig config() {
    return this.config;
  }

  public StandaloneStore store() {
    return this.store;
  }

  public LocalAuthoritativeCatalog catalog() {
    return this.catalog;
  }

  public DurableWriteLedger ledger() {
    return this.ledger;
  }

  public ApiIngestionManager ingestion() {
    return this.ingestion;
  }

  public MongotCursorManagerImpl cursors() {
    return this.cursors;
  }

  public DefaultIndexCatalog liveIndexes() {
    return this.liveIndexes;
  }

  /**
   * Coalesces notifications; the periodic backstop also finishes swaps and retiring generations.
   */
  public void catalogChanged() {
    if (!this.closed.get() && this.reconcileQueued.compareAndSet(false, true)) {
      this.reconciler.execute(
          () -> {
            this.reconcileQueued.set(false);
            reconcileSafely();
          });
    }
  }

  private void reconcileSafely() {
    if (this.closed.get()) {
      return;
    }
    try {
      reconcile();
      this.reconciliationFailure.set(Optional.empty());
    } catch (Exception e) {
      this.reconciliationFailure.set(Optional.of("Local catalog reconciliation failed"));
    }
  }

  private void maintainSafely() {
    if (this.closed.get()) {
      return;
    }
    try {
      this.store.maintain(System.currentTimeMillis() - this.config.retryRetention().toMillis());
      this.retentionFailure.set(Optional.empty());
    } catch (java.sql.SQLException e) {
      // Admission fails closed after maintenance errors until a successful retry.
      this.retentionFailure.set(Optional.of("Local retention maintenance failed"));
    }
  }

  private void reconcile() throws Exception {
    var desired =
        this.store.read(transaction -> transaction.allIndexes()).stream()
            .filter(index -> !index.dropped())
            .map(index -> StandaloneDefinitionValidator.restore(index.normalizedDefinition()))
            .toList();
    this.configManager.update(
        desired.stream()
            .filter(VectorIndexDefinition.class::isInstance)
            .map(VectorIndexDefinition.class::cast)
            .toList(),
        desired.stream()
            .filter(SearchIndexDefinition.class::isInstance)
            .map(SearchIndexDefinition.class::cast)
            .toList(),
        List.of(),
        Set.of(),
        Set.of());
    var desiredIds =
        desired.stream()
            .map(index -> index.getIndexId().toHexString())
            .collect(java.util.stream.Collectors.toSet());
    this.builds.keySet().removeIf(id -> !desiredIds.contains(id));
    for (String id : desiredIds) {
      var status = describeIndex(id);
      var prior = this.builds.get(id);
      if (status.getLifecycleState().equals("READY")) {
        if (prior != null && prior.revision() == status.getActiveRevision()) {
          this.serviceMetrics.elapsed("build.cutover", prior.startedNanos());
          this.builds.remove(id);
        }
      } else if (prior == null || prior.revision() != status.getDesiredRevision()) {
        this.builds.put(id, new BuildObservation(status.getDesiredRevision(), System.nanoTime()));
      }
    }
  }

  public boolean managementReady() {
    return !this.closed.get()
        && this.reconciliationFailure.get().isEmpty()
        && this.retentionFailure.get().isEmpty();
  }

  public StandaloneMetrics metrics() {
    return this.serviceMetrics;
  }

  /** Returns instance status and native engine meters; the RPC restricts this to administrators. */
  public ServiceStatus serviceStatus() throws Exception {
    var statistics = this.store.read(transaction -> transaction.statistics());
    var result =
        ServiceStatus.newBuilder()
            .setInstanceId(this.store.instanceId())
            .setManagementReady(managementReady())
            .setFailureMessage(
                this.retentionFailure.get().orElse(this.reconciliationFailure.get().orElse("")))
            .setStoreSchemaVersion(2)
            .setRetryRetentionMillis(this.config.retryRetention().toMillis())
            .setLogicalLogBytes(statistics.logBytes())
            .setRetryRequests(statistics.requests())
            .setRetentionPins(statistics.pins())
            .setSourceBytes(statistics.sourceBytes())
            .setStoreFileBytes(Files.size(this.config.dataPath().resolve("standalone.sqlite")))
            .setSqliteWalBytes(fileSize(this.config.dataPath().resolve("standalone.sqlite-wal")))
            .setFreeDiskBytes(Files.getFileStore(this.config.dataPath()).getUsableSpace());
    for (var index : this.store.read(transaction -> transaction.allIndexes())) {
      if (!index.dropped()) {
        result.addIndexes(describeIndex(index.id()).toBuilder().clearDesiredDefinitionBson());
      }
    }
    for (var meter : this.metrics.meterRegistry().getMeters()) {
      var metric = ServiceMetric.newBuilder().setName(meter.getId().getName());
      meter.getId().getTags().forEach(tag -> metric.putLabels(tag.getKey(), tag.getValue()));
      meter
          .measure()
          .forEach(
              measurement ->
                  metric.putMeasurements(
                      measurement.getStatistic().getTagValueRepresentation(),
                      measurement.getValue()));
      result.addMetrics(metric);
    }
    return result.build();
  }

  private static long fileSize(Path path) throws IOException {
    return Files.exists(path) ? Files.size(path) : 0;
  }

  /** Admission includes source/log and Lucene headroom; accepted mutations are never discarded. */
  public void checkWriteAdmission(long additionalBytes) {
    if (!managementReady()) {
      throw Status.UNAVAILABLE.withDescription("Local catalog is not ready").asRuntimeException();
    }
    try {
      long available = Files.getFileStore(this.config.dataPath()).getUsableSpace();
      long retained = this.store.read(StandaloneStore.Transaction::logBytes);
      if (additionalBytes < 0
          || available < additionalBytes
          || available - additionalBytes < this.config.minFreeDiskBytes()
          || retained > this.config.maxLogBytes() - additionalBytes) {
        throw Status.RESOURCE_EXHAUSTED
            .withDescription("Local disk or retained-log budget is exhausted")
            .asRuntimeException();
      }
    } catch (IOException | java.sql.SQLException e) {
      throw Status.UNAVAILABLE
          .withDescription("Cannot verify local storage admission")
          .withCause(e)
          .asRuntimeException();
    }
  }

  /**
   * Desired-catalog status includes uninitialized/failed resources and exact live/build identities.
   */
  public IndexDescription describeIndex(String id) {
    var desired = this.catalog.getIndex(id);
    if (desired.getLifecycleState().equals("DROPPED")) {
      return desired;
    }
    synchronized (this.configManager) {
      var result = desired.toBuilder();
      var live = this.liveIndexes.getIndexById(new ObjectId(id));
      var staged = this.configManager.configState.staged.getIndex(new ObjectId(id));
      @Var String state = "PENDING";
      if (live.isPresent()) {
        var generation = live.get();
        this.ingestion.watermarks(generation.getGenerationId()).ifPresent(result::setLive);
        if (this.ingestion.isGenerationReady(generation.getGenerationId())) {
          long active = generation.getDefinition().getDefinitionVersion().orElse(1L);
          result.setActiveRevision(active);
          state = active == desired.getDesiredRevision() ? "READY" : "BUILDING";
        } else {
          state =
              generation.getIndex().getStatus().getStatusCode()
                      == com.xgen.mongot.index.status.IndexStatus.StatusCode.FAILED
                  ? "FAILED"
                  : "BUILDING";
        }
        this.ingestion.failure(generation.getGenerationId()).ifPresent(result::setFailureMessage);
      }
      if (staged.isPresent()) {
        this.ingestion.watermarks(staged.get().getGenerationId()).ifPresent(result::setBuilding);
        this.ingestion.failure(staged.get().getGenerationId()).ifPresent(result::setFailureMessage);
        state = "BUILDING";
      }
      return result.setLifecycleState(state).build();
    }
  }

  @Override
  public void close() throws IOException {
    if (this.closed.compareAndSet(false, true)) {
      closeResources();
    }
  }

  private void closeResources() throws IOException {
    @Var
    @Nullable
    IOException failure = null;
    while (!this.resources.isEmpty()) {
      try {
        this.resources.removeFirst().close();
      } catch (Exception e) {
        if (failure == null) {
          failure = new IOException("Standalone shutdown failed", e);
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static LuceneConfig luceneConfig(Path path, Duration refreshInterval) {
    return LuceneConfig.create(
        path,
        Optional.of(refreshInterval),
        Optional.of(2),
        Optional.of(2),
        Optional.of(4),
        Optional.of(32.0),
        Optional.of(false),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(false),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(new com.xgen.mongot.config.util.HysteresisConfig(0.90, 0.95)),
        Optional.empty(),
        Optional.empty());
  }
}
