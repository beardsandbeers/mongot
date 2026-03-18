package com.xgen.mongot.cursor;

import com.xgen.mongot.cursor.batch.BatchCursorOptions;
import com.xgen.mongot.cursor.batch.QueryCursorOptions;
import com.xgen.mongot.index.lucene.explain.tracing.ExplainQueryState;
import com.xgen.mongot.index.query.QueryOptimizationFlags;
import com.xgen.mongot.index.query.SearchQuery;
import com.xgen.mongot.util.Bytes;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This cursor manager will be used to answer queries on indexes that are not present in the catalog
 * (i.e indexes that are not defined or unresolved). In this case, mongot returns empty batches
 * instead of throwing an error.
 */
class NullIndexCursorManager implements IndexCursorManager {

  private final CursorFactory cursorFactory;
  private final ConcurrentHashMap<Long, MongotCursor> cursors;

  NullIndexCursorManager(CursorFactory cursorFactory) {
    this.cursorFactory = cursorFactory;
    this.cursors = new ConcurrentHashMap<>();
  }

  @Override
  public SearchCursorInfo createCursor(
      String namespace,
      CursorQuery cursorQuery,
      QueryCursorOptions queryCursorOptions,
      QueryOptimizationFlags queryOptimizationFlags) {
    var cursorInfo = this.cursorFactory.getEmptyCursor(namespace);
    // We do need to keep track of the cursor here, so we can respond to
    // getNextBatch in a consistent way.
    this.cursors.put(cursorInfo.cursor.getId(), cursorInfo.cursor);
    return new SearchCursorInfo(cursorInfo.cursor.getId(), cursorInfo.metaResults);
  }

  @Override
  public IntermediateSearchCursorInfo createIntermediateCursors(
      String namespace,
      SearchQuery query,
      int intermediateVersion,
      QueryCursorOptions queryCursorOptions,
      QueryOptimizationFlags queryOptimizationFlags) {
    var cursorInfo = this.cursorFactory.getEmptyIntermediateCursors(namespace);
    // We do need to keep track of the cursor here, so we can respond to
    // getNextBatch in a consistent way.
    this.cursors.put(cursorInfo.searchCursor.getId(), cursorInfo.searchCursor);
    this.cursors.put(cursorInfo.metaCursor.getId(), cursorInfo.metaCursor);

    return new IntermediateSearchCursorInfo(
        cursorInfo.searchCursor.getId(), cursorInfo.metaCursor.getId());
  }

  @Override
  public MongotCursorResultInfo getNextBatch(
      long cursorId, Bytes resultsSizeLimit, BatchCursorOptions queryCursorOptions)
      throws MongotCursorNotFoundException, IOException {
    MongotCursor cursor =
        Optional.ofNullable(this.cursors.get(cursorId))
            .orElseThrow(() -> new MongotCursorNotFoundException(cursorId));

    MongotCursorResultInfo batch;
    try {
      batch = cursor.getNextBatch(resultsSizeLimit, queryCursorOptions);
    } catch (MongotCursorClosedException e) {
      throw new MongotCursorNotFoundException(cursorId);
    }
    // we know this is the last batch because all our cursors are empty, we do not need to keep
    // track of this cursor anymore.
    this.cursors.remove(cursorId);
    return batch;
  }

  @Override
  public void killCursor(long cursorId) {
    this.cursors.remove(cursorId);
  }

  @Override
  public Collection<Long> killAll() {
    // This method will only be called when CursorManager is shutting down (and is unreachable from
    // killIndexCursors(). The cursors here are empty, so we don't need to actually close them.
    this.cursors.clear();
    return Collections.emptyList();
  }

  @Override
  public boolean hasOpenCursors() {
    return false;
  }

  @Override
  public List<Long> killIdleCursorsSince(Instant idleSince) {
    // This method isn't really reachable, but either way, leaking empty cursors isn't a concern.
    return Collections.emptyList();
  }

  @Override
  public QueryBatchTimerRecorder getQueryBatchTimerRecorder() {
    return (Timer.Sample sample) -> {
      // do nothing
    };
  }

  @Override
  public Optional<ExplainQueryState> getExplainQueryState(long cursorId) {
    return Optional.empty();
  }

  @Override
  public void reportLongLivedCursors(Duration reportDuration, int reportedLongLivedCursorsSize) {
    // do nothing
  }
}
