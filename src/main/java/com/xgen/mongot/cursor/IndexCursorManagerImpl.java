package com.xgen.mongot.cursor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.xgen.mongot.cursor.batch.BatchCursorOptions;
import com.xgen.mongot.cursor.batch.QueryCursorOptions;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.IndexUnavailableException;
import com.xgen.mongot.index.InitializedIndex;
import com.xgen.mongot.index.InitializedSearchIndex;
import com.xgen.mongot.index.ReaderClosedException;
import com.xgen.mongot.index.lucene.explain.tracing.ExplainQueryState;
import com.xgen.mongot.index.query.InvalidQueryException;
import com.xgen.mongot.index.query.Query;
import com.xgen.mongot.index.query.QueryOptimizationFlags;
import com.xgen.mongot.index.query.SearchQuery;
import com.xgen.mongot.util.Bytes;
import com.xgen.mongot.util.concurrent.LockGuard;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the cursors of one index.
 *
 * <p>It is possible for multiple threads to be creating and killing cursors concurrently. However,
 * the IndexCursorManagerImpl::killAll method is exclusive with all other operations to ensure that
 * no cursors are left. It is beyond the responsibility of this class to prevent cursors from being
 * created right after the call to killAll(), this requires the index to be unavailable prior to
 * calling killAll.
 */
@SuppressWarnings("GuardedBy") // Uses LockGuard instead
class IndexCursorManagerImpl implements IndexCursorManager {

  private static final Logger LOG = LoggerFactory.getLogger(IndexCursorManagerImpl.class);

  private final ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock(true);
  private final Lock sharedLock = this.reentrantReadWriteLock.readLock();
  /* Used to prevent any new cursors from being created. */
  private final Lock exclusiveLock = this.reentrantReadWriteLock.writeLock();

  @VisibleForTesting final ConcurrentHashMap<Long, CursorAndStats> cursorAndStats;

  private final InitializedIndex index;

  /**
   * To keep unique cursor ids across different indexes, one CursorFactory is shared between all
   * IndexCursorManagerImpls.
   */
  private final CursorFactory cursorFactory;

  record ActiveCursorStats(
      Instant createdAt, Query query, LongAdder getMoreCount, LongAdder docsReturned) {

    public ActiveCursorStats(Query query) {
      this(Instant.now(), query, new LongAdder(), new LongAdder());
    }
  }

  record CursorAndStats(MongotCursor cursor, ActiveCursorStats stats) {}

  IndexCursorManagerImpl(InitializedIndex index, CursorFactory cursorFactory) {
    this.cursorAndStats = new ConcurrentHashMap<>();
    this.index = index;
    this.cursorFactory = cursorFactory;
  }

  @Override
  public SearchCursorInfo createCursor(
      String namespace,
      CursorQuery cursorQuery,
      QueryCursorOptions queryCursorOptions,
      QueryOptimizationFlags queryOptimizationFlags)
      throws IOException,
          InvalidQueryException,
          IndexUnavailableException,
          InterruptedException,
          ReaderClosedException {
    try (var ignored = LockGuard.with(this.sharedLock)) {
      var cursorInfo =
          this.cursorFactory.createCursor(
              namespace, this.index, cursorQuery, queryCursorOptions, queryOptimizationFlags);

      long cursorId = cursorInfo.cursor.getId();

      // We allow this method to be called concurrently with killCursor, there is no race here
      // because this cursor will only be visible after being inserted to this.cursorAndStats.
      // We might be registering empty cursors here as well (index in DOES_NOT_EXIST state), but
      // they will be killed after the one call to getNextBatch.
      this.cursorAndStats.put(
          cursorId,
          new CursorAndStats(cursorInfo.cursor, new ActiveCursorStats(cursorQuery.getQuery())));
      return new SearchCursorInfo(cursorId, cursorInfo.metaResults);
    }
  }

  @Override
  public IntermediateSearchCursorInfo createIntermediateCursors(
      String namespace,
      SearchQuery query,
      int intermediateVersion,
      QueryCursorOptions queryCursorOptions,
      QueryOptimizationFlags queryOptimizationFlags)
      throws IOException, InvalidQueryException, IndexUnavailableException, InterruptedException {
    try (var ignored = LockGuard.with(this.sharedLock)) {
      if (!(this.index instanceof InitializedSearchIndex searchIndex)) {
        throw new InvalidQueryException(
            "Cannot create intermediate cursors over vectorSearch index '%s'"
                .formatted(query.index()),
            InvalidQueryException.Type.STRICT);
      }

      var cursorInfo =
          this.cursorFactory.createIntermediateCursors(
              namespace, searchIndex, query, queryCursorOptions, queryOptimizationFlags);

      long searchCursorId = cursorInfo.searchCursor.getId();
      long metaCursorId = cursorInfo.metaCursor.getId();

      // We allow this method to be called concurrently with killCursor, there is no race here
      // because these cursors will only be visible after being inserted to this.cursorAndStats.
      // We might be registering empty cursors here as well (index in DOES_NOT_EXIST state), but
      // they will be killed after the one call to getNextBatch.
      this.cursorAndStats.put(
          searchCursorId,
          new CursorAndStats(cursorInfo.searchCursor, new ActiveCursorStats(query)));
      this.cursorAndStats.put(
          metaCursorId, new CursorAndStats(cursorInfo.metaCursor, new ActiveCursorStats(query)));
      return new IntermediateSearchCursorInfo(searchCursorId, metaCursorId);
    }
  }

  @Override
  public MongotCursorResultInfo getNextBatch(
      long cursorId, Bytes resultsSizeLimit, BatchCursorOptions queryCursorOptions)
      throws MongotCursorNotFoundException, IOException {
    try (var ignored = LockGuard.with(this.sharedLock)) {

      MongotCursor cursor =
          Optional.ofNullable(this.cursorAndStats.get(cursorId))
              .map(CursorAndStats::cursor)
              .orElseThrow(() -> new MongotCursorNotFoundException(cursorId));

      // It's possible that after retrieving the cursor, but before getting the next batch,
      // doKillCursor was called on the cursor. If this happens, cursor::getNextBatch will throw a
      // MongotCursorClosedException. From the point of view of the cursor manager, this is
      // equivalent to the cursor no longer existing, so catch it and rethrow it as a
      // MongotCursorNotFoundException.
      // Note that this relies on the fact that MongotCursor::getNextBatch and
      // MongotCursor::close are both synchronized.
      MongotCursorResultInfo batch;
      try {
        batch = cursor.getNextBatch(resultsSizeLimit, queryCursorOptions);
        var cursorStats =
            Optional.ofNullable(this.cursorAndStats.get(cursorId)).map(CursorAndStats::stats);
        cursorStats.ifPresent(
            stats -> {
              stats.getMoreCount().increment();
              stats.docsReturned().add(batch.batch.asArray().size());
            });
      } catch (MongotCursorClosedException e) {
        throw new MongotCursorNotFoundException(cursorId);
      }

      if (batch.exhausted) {
        doKillCursor(cursor);
      }

      return batch;
    }
  }

  @Override
  public void killCursor(long cursorId) {
    try (var ignored = LockGuard.with(this.sharedLock)) {
      Optional<MongotCursor> optionalCursor =
          Optional.ofNullable(this.cursorAndStats.get(cursorId)).map(CursorAndStats::cursor);

      if (optionalCursor.isEmpty()) {
        return;
      }
      // multiple threads may get to this point for the same cursorId, this is okay because
      // doKillCursor is thread safe and idempotent.
      MongotCursor cursor = optionalCursor.get();
      doKillCursor(cursor);
    }
  }

  /**
   * Note that it is possible to create new cursors right after this method is called, so we rely on
   * higher level synchronization to make the index unavailable prior to calling this method. Note
   * that IndexCursorManagerImpl does not know if an index has been removed from the catalog, this
   * has to be handled by MongotCursorManagerImpl.
   */
  @Override
  public Collection<Long> killAll() {
    try (var ignored = LockGuard.with(this.exclusiveLock)) {
      // Since we are holding the exclusive lock, this.cursorAndStats can not be modified by other
      // threads
      // A copy is required because this.cursorAndStats is modified.
      ImmutableSet<Long> cursorIds = ImmutableSet.copyOf(this.cursorAndStats.keySet());
      ImmutableSet<MongotCursor> cursors =
          this.cursorAndStats.values().stream()
              .map(CursorAndStats::cursor)
              .collect(ImmutableSet.toImmutableSet());

      for (var cursor : cursors) {
        doKillCursor(cursor);
      }

      return cursorIds;
    }
  }

  /** Returns a list of cursor ids that were killed if they were inactive since idleSince. */
  @Override
  public List<Long> killIdleCursorsSince(Instant idleSince) {
    try (var ignored = LockGuard.with(this.sharedLock)) {
      // it is enough to hold the sharedLock here. From the point of view of an idle cursor, this
      // is the same as calling killCursor(cursorId) concurrently, which we also use the sharedLock
      // for.
      // It is possible that this.cursorAndStats changes while we are here. If a cursor has just
      // been
      // added, then surely it isn't idle. If a cursor was just killed, then we might try to
      // kill it again, which is redundant but not erroneous.
      List<MongotCursor> idleCursors =
          this.cursorAndStats.values().stream()
              .map(CursorAndStats::cursor)
              .filter(cursor -> cursor.getLastActive().isBefore(idleSince))
              .toList();

      if (!idleCursors.isEmpty()) {
        LOG.atWarn()
            .addKeyValue("idleSinceTime", idleSince)
            .addKeyValue(
                "cursorIds",
                idleCursors.stream().map(MongotCursor::getId).collect(Collectors.toList()))
            .log("killing idle cursors");

        for (var cursor : idleCursors) {
          doKillCursor(cursor);
        }
      }
      return idleCursors.stream().map(MongotCursor::getId).collect(Collectors.toList());
    }
  }

  @Override
  public void reportLongLivedCursors(Duration reportDuration, int reportedLongLivedCursorsSize) {
    Instant now = Instant.now();
    this.cursorAndStats.entrySet().stream()
        .filter(entry -> entry.getValue().stats().createdAt().isBefore(now.minus(reportDuration)))
        .sorted(Comparator.comparing(entry -> entry.getValue().stats().createdAt()))
        .limit(reportedLongLivedCursorsSize)
        .forEach(
            entry -> {
              LOG.atWarn()
                  .addKeyValue(
                      "lifeSpan", Duration.between(entry.getValue().stats().createdAt(), now))
                  .addKeyValue("cursorId", entry.getKey())
                  .addKeyValue(
                      "query",
                      StringUtils.abbreviate(
                          entry.getValue().stats().query().toString(), "...", 1000))
                  .addKeyValue("getMore", entry.getValue().stats().getMoreCount().longValue())
                  .addKeyValue("docsReturned", entry.getValue().stats().docsReturned().longValue())
                  .log("Long-lived cursors");
            });
  }

  private void doKillCursor(MongotCursor cursor) {
    try {
      // NOTE: multiple threads may be calling this. We rely on MongotCursor::close being
      // synchronized and idempotent.
      cursor.close();
    } catch (IOException e) {
      LOG.atError()
          .addKeyValue("cursorId", cursor.getId())
          .setCause(e)
          .log("Caught exception attempting to close cursor");
    }

    this.cursorAndStats.remove(cursor.getId());
  }

  /**
   * If any cursors are open, or an explain or search batch is being gathered. In order to ensure
   * that no new cursors are opened after calling this method, the index should be in an unavailable
   * state or removed from the index catalog.
   */
  @Override
  public boolean hasOpenCursors() {
    boolean noSharedLocksHeld = this.exclusiveLock.tryLock();

    if (noSharedLocksHeld) {
      try {
        // If we hold the exclusive lock, no queries are currently in the critical section.
        return !this.cursorAndStats.isEmpty();
      } finally {
        this.exclusiveLock.unlock();
      }
    }

    // Otherwise, at least one query is running.
    return true;
  }

  @Override
  public QueryBatchTimerRecorder getQueryBatchTimerRecorder() {
    IndexMetricsUpdater.QueryingMetricsUpdater updater =
        this.index.getMetricsUpdater().getQueryingMetricsUpdater();

    return (Timer.Sample sample) -> {
      long durationNs = sample.stop(updater.getSearchResultBatchLatencyTimer());
      updater.recordDynamicFeatureFlagLatencyTimer(durationNs);
      updater.getSearchAndGetMoreCommandCounter().increment();
    };
  }

  @Override
  public Optional<ExplainQueryState> getExplainQueryState(long cursorId)
      throws MongotCursorNotFoundException {
    try (var ignored = LockGuard.with(this.sharedLock)) {
      MongotCursor cursor =
          Optional.ofNullable(this.cursorAndStats.get(cursorId))
              .map(CursorAndStats::cursor)
              .orElseThrow(() -> new MongotCursorNotFoundException(cursorId));
      try {
        return cursor.getExplainQueryState();
      } catch (MongotCursorClosedException e) {
        throw new MongotCursorNotFoundException(cursorId);
      }
    }
  }
}
