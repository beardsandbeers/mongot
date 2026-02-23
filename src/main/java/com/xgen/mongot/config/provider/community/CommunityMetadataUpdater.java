package com.xgen.mongot.config.provider.community;

import static com.xgen.mongot.util.Check.checkState;

import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.xgen.mongot.catalogservice.IndexStatsEntry;
import com.xgen.mongot.catalogservice.IndexStatsEntryMapper;
import com.xgen.mongot.catalogservice.MetadataService;
import com.xgen.mongot.catalogservice.MetadataServiceException;
import com.xgen.mongot.config.manager.CachedIndexInfoProvider;
import com.xgen.mongot.index.IndexInformation;
import com.xgen.mongot.util.Crash;
import com.xgen.mongot.util.concurrent.Executors;
import com.xgen.mongot.util.concurrent.NamedScheduledExecutorService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible for updating to the serverState metadata collection indicating current server is
 * alive plus writing to the indexStats collection the state of the current indexes on the server.
 */
public class CommunityMetadataUpdater {

  private static final Logger LOG = LoggerFactory.getLogger(CommunityMetadataUpdater.class);

  private final CommunityServerInfo serverInfo;
  private final MetadataService metadataService;
  private final CachedIndexInfoProvider indexInfoProvider;
  private final NamedScheduledExecutorService executorService;
  private final Duration runFrequency;

  // We cache the keys and hash-codes of all entries stored in metadata for the current server. This
  // way we can identify if an object changed (by its hash code changing) and only update their
  // metadata representation when that happens to avoid overloading mongod with no-op write traffic.
  private final Map<IndexStatsEntry.IndexStatsKey, Integer> indexStatsCache = new HashMap<>();

  @GuardedBy("this")
  private volatile boolean startupCompleted = false;

  @GuardedBy("this")
  private volatile boolean closed = false;

  public CommunityMetadataUpdater(
      CommunityServerInfo serverInfo,
      MetadataService metadataService,
      CachedIndexInfoProvider indexInfoProvider,
      MeterRegistry meterRegistry,
      Duration runFrequency) {
    this.serverInfo = serverInfo;
    this.metadataService = metadataService;
    this.indexInfoProvider = indexInfoProvider;
    this.runFrequency = runFrequency;
    this.executorService =
        Executors.singleThreadScheduledExecutor(
            "metadata-updater", Thread.MAX_PRIORITY, meterRegistry);
  }

  public void start() {
    LOG.info("Beginning periodic community metadata updater");

    this.executorService.scheduleWithFixedDelay(
        () -> Crash.because("community metadata updater failed").ifThrows(this::run),
        0,
        this.runFrequency.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  public synchronized void stop() {
    LOG.info("Shutting down...");
    Executors.shutdownOrFail(this.executorService);
    this.closed = true;
  }

  protected synchronized void run() {
    checkState(!this.closed, "cannot call update() after close()");

    if (!this.startupCompleted) {
      if (!initializeMetadataIndexes() || !initializeCache()) {
        LOG.info("Waiting for database to startup to initialize indexes...");
        return;
      }
      LOG.info("Indexes built and cache initialized, starting update thread");
      this.startupCompleted = true;
    }

    updateServerState();
    updateIndexStats();
  }

  /**
   * Before starting to update the metadata collections, we need to initialize the indexes if they
   * do not yet exist.
   *
   * <p>This must be called within the executor thread, off of the main startup thread as we need to
   * wait for mongod to be initialized before creating the indexes and starting to update the
   * metadata collections.
   *
   * <p>This operation is idempotent so can be called by each server on restart.
   *
   * @return - true if we successfully created the indexes. Else false, indicating mongod is not
   *     available yet so we need to wait and try again.
   */
  private boolean initializeMetadataIndexes() {
    try {
      this.metadataService.getIndexStats().createCollectionIndexes();
      return true;
    } catch (MetadataServiceException e) {
      // Error indicates mongod isn't available yet.
      LOG.info(
          "Failed creating index stats indexes."
              + " This typically indicates mongod has not started yet."
              + " We will backoff and retry on next run.",
          e);
      return false;
    }
  }

  /**
   * On startup initialize the indexStatscache with the last known values of all the indexes on this
   * server so we can refresh their state going forward.
   *
   * @return true if we successfully loaded the cache, else false.
   */
  private boolean initializeCache() {
    try {
      List<IndexStatsEntry> indexStatsEntries =
          this.metadataService
              .getIndexStats()
              .list(IndexStatsEntry.serverIdFilter(this.serverInfo.id()));
      reloadIndexStatsCache(indexStatsEntries);
      return true;
    } catch (MetadataServiceException e) {
      LOG.warn(
          "Failed listing index stats entries to populate the cache."
              + " We will backoff and retry on next run.",
          e);
      return false;
    }
  }

  /** Updates the current server's serverState entry with the latest heartbeatbeat ts. */
  private void updateServerState() {
    try {
      this.metadataService.getServerState().upsert(this.serverInfo.generateServerStateEntry());
    } catch (MetadataServiceException e) {
      // Log but catch any errors writing to the server state collection. There's no need to
      // propagate the error and crash the process. The main impact from not being able to update
      // our server state would be listSearchIndexes filtering out this server's indexes which while
      // not great is better than crashing. When writes resume listSearchIndexes will naturally
      // recover.
      LOG.error("error updating server status", e);
    }
  }

  /**
   * Keeps the indexStats metadata for this server in sync based on the current state of the server.
   *
   * <ol>
   *   <li>If a new index was added, adds the indexStats entry into metadata.
   *   <li>If an index was deleted, removes the indexStats entry from metadata.
   *   <li>If an index definition or state changes, updates the existing metadata object.
   *   <li>If an index was unchanged since the last run, does nothing.
   * </ol>
   */
  private void updateIndexStats() {
    this.indexInfoProvider.refreshIndexInfos();
    List<IndexInformation> indexInfos = this.indexInfoProvider.getIndexInfos();

    Map<IndexStatsEntry.IndexStatsKey, IndexStatsEntry> indexStatsEntries =
        indexInfos.stream()
            .map(i -> IndexStatsEntryMapper.fromIndexInformation(i, this.serverInfo.id()))
            .collect(
                Collectors.toMap(
                    IndexStatsEntry::key,
                    i -> i,
                    (i1, i2) -> {
                      LOG.atWarn()
                          .addKeyValue("key", i1.key())
                          .addKeyValue("entry1", i1)
                          .addKeyValue("entry2", i2)
                          .log("Duplicate index stats entries for a given key");
                      // A conflict here implies there two indexes were returned from the
                      // indexInfoProvider.getIndexInfos() with the same index id.
                      // This is considered unexpected state and instead of trying to recover bubble
                      // up the exception which will crash the process.
                      throw new IllegalArgumentException(
                          "Duplicate index stats entries for a given key");
                    }));

    // Find indexes that are in the cache but no longer returned by the indexInfoProvider to delete
    // from metadata.
    Set<IndexStatsEntry.IndexStatsKey> indexStatsToDelete =
        this.indexStatsCache.keySet().stream()
            .filter(i -> !indexStatsEntries.containsKey(i))
            .collect(Collectors.toSet());

    // If an index changed since the last time we wrote it to metadata the hash code will have
    // changed indicating we need to update their representation in metadata.
    // There is a possibility that an index changed but due to a hashCode collisions the derived
    // hash code does not change even if the index changed. We consider such a collision so rare and
    // the impact minimal (not updating the indexStats for the index) that we chose to leave this
    // risk in place.
    Set<IndexStatsEntry> indexStatsToUpdate =
        indexStatsEntries.values().stream()
            .filter(
                entry -> {
                  Integer cachedHash = this.indexStatsCache.get(entry.key());
                  return cachedHash == null || cachedHash != entry.hashCode();
                })
            .collect(Collectors.toSet());

    try {
      this.metadataService.getIndexStats().deleteAll(indexStatsToDelete);
      this.metadataService.getIndexStats().upsertAll(indexStatsToUpdate);

      // If we failed the write to metadata we won't update the cache and will try again on the next
      // retry. Both the deletes and upsert operations are idempotent and can be safely retried if
      // there was some sore of grey failure where the operation went through but we still got an
      // exception.
      reloadIndexStatsCache(indexStatsEntries.values());
    } catch (MetadataServiceException e) {
      // Log but catch any errors writing to the index stats collection. There's no need to
      // propagate the error and crash the process as we already expect data in the collection to be
      // eventually consistent.
      LOG.error("error updating index stats", e);
    }
  }

  /**
   * Clears the indexStatsCache and replaces it with all the elements in the provided list.
   *
   * @param indexStatsEntries the entries to load into the cache
   */
  private void reloadIndexStatsCache(Collection<IndexStatsEntry> indexStatsEntries) {
    this.indexStatsCache.clear();
    for (IndexStatsEntry entry : indexStatsEntries) {
      Integer previous = this.indexStatsCache.put(entry.key(), entry.hashCode());
      if (previous != null) {
        LOG.atWarn()
            .addKeyValue("key", entry.key())
            .addKeyValue("previousHashCode", previous)
            .addKeyValue("newHashCode", entry.hashCode())
            .log("Found duplicate index stats keys");
        // A conflict here implies there two indexes were returned from the
        // indexInfoProvider.getIndexInfos() with the same index id.
        // This is considered unexpected state and instead of trying to recover bubble up the
        // exception which will crash the process.
        throw new IllegalArgumentException("duplicate index keys in indexStats collection");
      }
    }
  }
}
