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
import com.xgen.mongot.standalone.catalog.LocalIndexCatalog;
import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.ServiceMetric;
import com.xgen.mongot.standalone.grpc.ServiceStatus;
import com.xgen.mongot.standalone.ingestion.ApiIngestionManager;
import com.xgen.mongot.standalone.metrics.StandaloneMetrics;
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
  private final StandaloneConfig config;
  private final LocalIndexCatalog catalog;
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

  /** Opens the index metadata and composes the Lucene lifecycle without a source database. */
  public StandaloneRuntime(StandaloneConfig config) throws Exception {
    this.config = config;
    this.catalog = new LocalIndexCatalog(config.dataPath());
    this.resources.addFirst(this.catalog);
    try {
      this.metrics = MeterAndFtdcRegistry.createWithSimpleRegistries();
      this.serviceMetrics = new StandaloneMetrics(this.metrics.meterRegistry());
      this.resources.addFirst(
          () -> {
            this.metrics.ftdcRegistry().close();
            this.metrics.meterRegistry().close();
          });
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
              this.liveIndexes, initialized, config.documentLimits(), config.commitInterval());
      this.resources.addFirst(() -> this.ingestion.shutdown().get(30, TimeUnit.SECONDS));
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
          this.catalog.definitions().stream()
              .map(index -> index.getIndexId())
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
              dynamicFlags);
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

  public LocalIndexCatalog catalog() {
    return this.catalog;
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

  private void reconcile() throws Exception {
    var desired = this.catalog.definitions();
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
  }

  public boolean managementReady() {
    return !this.closed.get() && this.reconciliationFailure.get().isEmpty();
  }

  public StandaloneMetrics metrics() {
    return this.serviceMetrics;
  }

  /** Returns instance status and native engine meters; the RPC restricts this to administrators. */
  public ServiceStatus serviceStatus() throws Exception {
    var result =
        ServiceStatus.newBuilder()
            .setManagementReady(managementReady())
            .setFailureMessage(this.reconciliationFailure.get().orElse(""))
            .setFreeDiskBytes(Files.getFileStore(this.config.dataPath()).getUsableSpace());
    for (var index : this.catalog.definitions()) {
      result.addIndexes(
          describeIndex(index.getIndexId().toHexString()).toBuilder().clearDesiredDefinitionBson());
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

  /** Checks Lucene disk headroom before applying a pushed batch. */
  public void checkWriteAdmission(long additionalBytes) {
    if (!managementReady()) {
      throw Status.UNAVAILABLE.withDescription("Index catalog is not ready").asRuntimeException();
    }
    try {
      long available = Files.getFileStore(this.config.dataPath()).getUsableSpace();
      if (additionalBytes < 0
          || available < additionalBytes
          || available - additionalBytes < this.config.minFreeDiskBytes()) {
        throw Status.RESOURCE_EXHAUSTED
            .withDescription("Lucene disk headroom is exhausted")
            .asRuntimeException();
      }
    } catch (IOException e) {
      throw Status.UNAVAILABLE
          .withDescription("Cannot verify disk headroom")
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
        result.setGenerationId(generation.getGenerationId().uniqueString());
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
      }
      if (staged.isPresent()) {
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
