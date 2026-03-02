package com.xgen.mongot.replication.mongodb.initialsync;

import static com.xgen.mongot.index.definition.MaterializedViewIndexDefinitionGeneration.isMaterializedViewBasedIndex;
import static com.xgen.mongot.replication.mongodb.initialsync.InitialSyncManager.awaitShutdown;
import static com.xgen.mongot.replication.mongodb.initialsync.InitialSyncManager.getResultOrThrow;
import static com.xgen.mongot.replication.mongodb.initialsync.InitialSyncManager.supplyAsync;
import static com.xgen.mongot.util.Check.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.errorprone.annotations.Var;
import com.mongodb.MongoNamespace;
import com.xgen.mongot.logging.DefaultKeyValueLogger;
import com.xgen.mongot.metrics.MetricsFactory;
import com.xgen.mongot.replication.mongodb.common.ChangeStreamResumeInfo;
import com.xgen.mongot.replication.mongodb.common.IndexCommitUserData;
import com.xgen.mongot.replication.mongodb.common.InitialSyncException;
import com.xgen.mongot.replication.mongodb.common.InitialSyncResumeInfo;
import com.xgen.mongot.util.BsonUtils;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.BsonValue;

public class BufferlessInitialSyncManager implements InitialSyncManager {

  private final DefaultKeyValueLogger logger;
  private final InitialSyncContext context;
  private final BufferlessCollectionScannerFactory collectionScannerFactory;
  private final BufferlessChangeStreamApplierFactory changeStreamApplierFactory;
  private final ServerClusterTimeProvider clusterTimeProvider;
  private final Duration collectionScanTime;
  private final Optional<InitialSyncResumeInfo> resumeInfo;

  /* Tracks the duration of a collection scan */
  private final Timer collectionScanTimer;
  /* Tracks the duration of applying change stream events */
  private final Timer changeStreamTimer;

  @VisibleForTesting
  BufferlessInitialSyncManager(
      InitialSyncContext context,
      BufferlessCollectionScannerFactory collectionScannerFactory,
      BufferlessChangeStreamApplierFactory changeStreamApplierFactory,
      ServerClusterTimeProvider clusterTimeProvider,
      Duration collectionScanTime,
      Optional<InitialSyncResumeInfo> resumeInfo,
      MetricsFactory metricsFactory) {
    HashMap<String, Object> defaultKeyValues = new HashMap<>();
    defaultKeyValues.put("indexId", context.getIndexId());
    defaultKeyValues.put("generationId", context.getGenerationId());
    this.logger =
        DefaultKeyValueLogger.getLogger(BufferlessInitialSyncManager.class, defaultKeyValues);

    this.context = context;
    this.collectionScannerFactory = collectionScannerFactory;
    this.changeStreamApplierFactory = changeStreamApplierFactory;
    this.clusterTimeProvider = clusterTimeProvider;
    this.collectionScanTime = collectionScanTime;
    this.resumeInfo = resumeInfo;
    this.collectionScanTimer = metricsFactory.timer("collectionScanTime");
    this.changeStreamTimer = metricsFactory.timer("changeStreamTime");
  }

  static InitialSyncManagerFactory factory(
      Duration collectionScanTime,
      Duration changeStreamCatchupTimeout,
      Duration changeStreamLagTime,
      boolean avoidNaturalOrderScanSyncSourceChangeResync,
      List<String> excludedChangestreamFields,
      boolean matchCollectionUuidForUpdateLookup,
      MetricsFactory metricsFactory) {
    return (initialSyncContext, mongoClient, namespace, resumeInfo) ->
        create(
            initialSyncContext,
            mongoClient,
            namespace,
            collectionScanTime,
            changeStreamCatchupTimeout,
            changeStreamLagTime,
            excludedChangestreamFields,
            matchCollectionUuidForUpdateLookup,
            resumeInfo,
            metricsFactory,
            avoidNaturalOrderScanSyncSourceChangeResync);
  }

  private static BufferlessInitialSyncManager create(
      InitialSyncContext initialSyncContext,
      InitialSyncMongoClient mongoClient,
      MongoNamespace namespace,
      Duration collectionScanTime,
      Duration changeStreamCatchupTimeout,
      Duration changeStreamLagTime,
      List<String> excludedChangestreamFields,
      boolean matchCollectionUuidForUpdateLookup,
      Optional<InitialSyncResumeInfo> resumeInfo,
      MetricsFactory metricsFactory,
      boolean avoidNaturalOrderScanSyncSourceChangeResync) {
    BufferlessCollectionScannerFactory collectionScannerFactory =
        (context, lastId) -> {
          if (isMaterializedViewBasedIndex(
              context.indexDefinitionGeneration.getIndexDefinition())) {
            return new AutoEmbeddingSortedIdCollectionScanner(
                Clock.systemUTC(), context, mongoClient, lastId, metricsFactory);
          } else {
            return new BufferlessCollectionScanner(
                Clock.systemUTC(),
                context,
                mongoClient,
                lastId,
                metricsFactory,
                avoidNaturalOrderScanSyncSourceChangeResync);
          }
        };
    BufferlessChangeStreamApplierFactory changeStreamApplierFactory =
        (BsonTimestamp highWaterMark, boolean isFreshStart) ->
            new BufferlessChangeStreamApplier(
                Clock.systemUTC(),
                changeStreamCatchupTimeout,
                changeStreamLagTime,
                excludedChangestreamFields,
                matchCollectionUuidForUpdateLookup,
                initialSyncContext,
                mongoClient,
                namespace,
                highWaterMark,
                metricsFactory,
                avoidNaturalOrderScanSyncSourceChangeResync,
                isFreshStart);

    return new BufferlessInitialSyncManager(
        initialSyncContext,
        collectionScannerFactory,
        changeStreamApplierFactory,
        mongoClient::getMaxValidMajorityReadOptime,
        collectionScanTime,
        resumeInfo,
        metricsFactory);
  }

  /**
   * Runs the initial sync, returning after the index has been committed. Returns a
   * ChangeStreamResumeInfo indicating the position in a change stream where the sync has applied
   * to.
   */
  @Override
  public ChangeStreamResumeInfo sync() throws InitialSyncException {
    // If we're resuming a sync, read the change stream high water mark and the id of the last doc
    // indexed during collection scan from the resume info.
    @Var BsonTimestamp highWaterMark;
    @Var BsonValue lastScannedToken;
    if (this.resumeInfo.isPresent()) {
      // This should never happen because useNaturalOrderScan should be set properly when requests
      // are enqueued into initial sync queue.
      if ((this.resumeInfo.get().isBufferlessIdOrderInitialSyncResumeInfo()
              && this.context.useNaturalOrderScan())
          || (this.resumeInfo.get().isBufferlessNaturalOrderInitialSyncResumeInfo()
              && !this.context.useNaturalOrderScan())) {
        this.logger
            .atWarn()
            .addKeyValue("useNaturalOrderScan", this.context.useNaturalOrderScan())
            .log("resumeInfo does not match with request context, retry the initial sync");
        throw InitialSyncException.createInvalidated(this.resumeInfo.get());
      }
      highWaterMark = this.resumeInfo.get().getResumeOperationTime();
      lastScannedToken = this.resumeInfo.get().getResumeToken();
      this.logger
          .atInfo()
          .addKeyValue("lastScannedToken", lastScannedToken)
          .log("Collection scan will resume from the last doc indexed during collection scan.");
    } else {
      highWaterMark = this.clusterTimeProvider.getCurrentClusterTime();
      lastScannedToken =
          this.context.useNaturalOrderScan() ? new BsonDocument() : BsonUtils.MIN_KEY;
    }
    // isFreshStart is true when we're not resuming from a crash (resumeInfo is empty).
    // For fresh starts, we can safely use highWaterMark + 1 since the collection scan already
    // captured everything at highWaterMark. For resumes, we must use highWaterMark (inclusive)
    // to avoid missing events from multi-document transactions.
    boolean isFreshStart = this.resumeInfo.isEmpty();
    BufferlessChangeStreamApplier changeStreamApplier =
        this.changeStreamApplierFactory.create(highWaterMark, isFreshStart);

    Stopwatch stopwatch = Stopwatch.createStarted();
    this.logger
        .atInfo()
        .addKeyValue("useNaturalOrderScan", this.context.useNaturalOrderScan())
        .addKeyValue("startOpTime", highWaterMark)
        .addKeyValue(
            "resumeOpTime", this.resumeInfo.map(InitialSyncResumeInfo::getResumeOperationTime))
        .addKeyValue("indexId", this.context.getIndexId())
        .addKeyValue("generationId", this.context.getGenerationId())
        .addKeyValue("lastScannedToken", this.resumeInfo.map(InitialSyncResumeInfo::getResumeToken))
        .addKeyValue("hostName", this.resumeInfo.map(InitialSyncResumeInfo::getSyncSourceHost))
        .log("Beginning initial sync.");

    try (changeStreamApplier) {
      // Continue the initial sync until the entire collection has been scanned.
      @Var boolean continueSync = true;
      while (continueSync) {
        // Update the last scanned id and whether we need to continue scanning. If we're done
        // scanning, catch up with the change stream one last time before finishing the initial
        // sync.

        var collectionScanTimer = Stopwatch.createStarted();
        BufferlessCollectionScanner.Result scanResult =
            scanCollection(highWaterMark, lastScannedToken);
        this.collectionScanTimer.record(collectionScanTimer.stop().elapsed());

        continueSync = scanResult.getContinueSync();
        lastScannedToken = scanResult.getLastScannedToken();

        // Apply change stream events and update the high water mark.
        var changeStreamTimer = Stopwatch.createStarted();
        highWaterMark =
            applyChangeStreamEvents(changeStreamApplier, lastScannedToken, continueSync);
        this.changeStreamTimer.record(changeStreamTimer.stop().elapsed());
      }
    }

    Optional<ChangeStreamResumeInfo> changeStreamResumeInfo = changeStreamApplier.getResumeInfo();

    this.logger
        .atInfo()
        .addKeyValue("useNaturalOrderScan", this.context.useNaturalOrderScan())
        .addKeyValue("duration", stopwatch)
        .addKeyValue("indexId", this.context.getIndexId())
        .addKeyValue("generationId", this.context.getGenerationId())
        .log("Completed initial sync. Beginning first commit.");
    checkState(
        changeStreamResumeInfo.isPresent(),
        "Change stream application completed without setting ChangeStreamResumeInfo");

    IndexCommitUserData commitUserData =
        IndexCommitUserData.createChangeStreamResume(
            changeStreamResumeInfo.get(), this.context.getIndexFormatVersion());
    this.context.indexer.updateCommitUserData(commitUserData);
    try {
      this.context.indexer.commit();
    } catch (Exception e) {
      this.logger
          .atWarn()
          .addKeyValue("indexId", this.context.getIndexId())
          .addKeyValue("generationId", this.context.getGenerationId())
          .log("First commit failed (likely due to interruption).");
      // If replication is shutdown, this exception will be ignored by the ReplicationIndexManager.
      // Otherwise, it's likely to be caused by some disk issues. We will retry with backoff.
      throw InitialSyncException.createResumableTransient(e);
    }

    this.logger
        .atInfo()
        .addKeyValue("indexId", this.context.getIndexId())
        .addKeyValue("generationId", this.context.getGenerationId())
        .log("Completed first commit.");
    return changeStreamResumeInfo.get();
  }

  /**
   * Returns the shutdown timeout based on index type. Auto-embedding indexes use a longer timeout
   * to accommodate slow external embedding API calls.
   */
  private Duration getShutdownTimeout() {
    return isMaterializedViewBasedIndex(this.context.indexDefinitionGeneration.getIndexDefinition())
        ? InitialSyncManager.AUTO_EMBEDDING_SHUTDOWN_TIMEOUT
        : InitialSyncManager.SHUTDOWN_TIMEOUT;
  }

  /**
   * Issues a new collection scan cursor with the change stream high water mark as the
   * readConcern.afterClusterTime, ensuring the scan represents a view of the collection from an
   * opTime after the last event applied. Scan for some time, limited by collectionScanTime.
   */
  private BufferlessCollectionScanner.Result scanCollection(
      BsonTimestamp highWaterMark, BsonValue lastScannedToken) throws InitialSyncException {
    BufferlessCollectionScanner collectionScanner =
        this.collectionScannerFactory.create(
            this.context.withProgress(highWaterMark), lastScannedToken);

    CompletableFuture<BufferlessCollectionScanner.Result> scanFuture =
        supplyAsync(
            () -> collectionScanner.scanWithTimeLimit(this.collectionScanTime),
            String.format("%s %s", this.context.uniqueString(), "CollectionScanner"));

    Duration shutdownTimeout = getShutdownTimeout();

    Runnable shutDown =
        () -> {
          collectionScanner.signalShutdown();
          awaitShutdown(shutdownTimeout, scanFuture);
        };

    return getResultOrThrow(scanFuture, "waiting for collection scan", shutDown, this.logger);
  }

  /**
   * Apply events until we have 1) encountered a batch that is past stopAfterOpTime, signalling that
   * we have caught up to where we ended the most recent collection scan phase, and 2) spent some
   * time trying to further "catch up" with the change stream.
   */
  private BsonTimestamp applyChangeStreamEvents(
      BufferlessChangeStreamApplier changeStreamApplier,
      BsonValue lastScannedToken,
      boolean continueSync)
      throws InitialSyncException {
    // Use the opTime after a collection scan phase to signal the change stream event application
    // phase to switch back after witnessing a batch that is past this opTime.
    BsonTimestamp stopAfterOpTime = this.clusterTimeProvider.getCurrentClusterTime();

    CompletableFuture<BsonTimestamp> applierFuture =
        supplyAsync(
            () -> changeStreamApplier.applyEvents(lastScannedToken, stopAfterOpTime, continueSync),
            String.format(
                "%s %s",
                this.context.uniqueString(), BufferlessChangeStreamApplier.class.getSimpleName()));

    Duration shutdownTimeout = getShutdownTimeout();

    Runnable changeStreamShutdown =
        () -> {
          changeStreamApplier.signalShutdown();
          awaitShutdown(shutdownTimeout, applierFuture);
        };

    return getResultOrThrow(
        applierFuture,
        "waiting for change stream events to be applied",
        changeStreamShutdown,
        this.logger);
  }
}
