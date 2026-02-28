package com.xgen.mongot.index.lucene.merge;

import com.google.common.base.Stopwatch;
import com.google.errorprone.annotations.Var;
import com.xgen.mongot.index.version.GenerationId;
import com.xgen.mongot.metrics.MetricsFactory;
import com.xgen.mongot.metrics.ServerStatusDataExtractor;
import com.xgen.mongot.metrics.ServerStatusDataExtractor.LuceneMeterData;
import com.xgen.mongot.metrics.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.MergeScheduler;
import org.apache.lucene.index.MergeTrigger;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.InfoStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InstrumentedConcurrentMergeScheduler tracks and exports prometheus metrics for merges and allows
 * for the same MergeScheduler to be shared by multiple lucene indices.
 *
 * <p>This scheduler is shared by all indices but should not be passed directly to IndexWriter.
 */
public class InstrumentedConcurrentMergeScheduler extends ConcurrentMergeScheduler {
  /**
   * IndexPartitionIdentifier is a wrapper of GenerationId and optional indexPartitionId. It is used
   * to identify an index-partition in all indexes.
   *
   * <p>When there is only one index-partition, indexPartitionId will be empty.
   */
  static class IndexPartitionIdentifier {
    private final GenerationId generationId;
    private final Optional<Integer> indexPartitionId;

    // TODO(CLOUDP-280897): We don't have the index type anymore. Bring it back needs extra plumbing
    // of the IndexDefinitionGeneration.
    IndexPartitionIdentifier(GenerationId generationId, Optional<Integer> indexPartitionId) {
      this.generationId = generationId;
      this.indexPartitionId = indexPartitionId;
    }

    GenerationId getGenerationId() {
      return this.generationId;
    }

    Optional<Integer> getIndexPartitionId() {
      return this.indexPartitionId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      IndexPartitionIdentifier that = (IndexPartitionIdentifier) o;
      return Objects.equals(this.generationId, that.generationId)
          && Objects.equals(this.indexPartitionId, that.indexPartitionId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.generationId, this.indexPartitionId);
    }

    @Override
    public String toString() {
      return "IndexPartitionIdentifier(generationIdLogString="
          + this.generationId.uniqueString()
          + ", indexPartition="
          + this.indexPartitionId
          + ")";
    }
  }

  // A filter pattern
  static class TaggedMergeSource implements MergeScheduler.MergeSource {
    private final MergeScheduler.MergeSource in;
    private final IndexPartitionIdentifier indexPartitionIdentifier;

    TaggedMergeSource(
        MergeScheduler.MergeSource in, IndexPartitionIdentifier indexPartitionIdentifier) {
      this.in = in;
      this.indexPartitionIdentifier = indexPartitionIdentifier;
    }

    public IndexPartitionIdentifier getIndexPartitionIdentifier() {
      return this.indexPartitionIdentifier;
    }

    @Override
    public MergePolicy.OneMerge getNextMerge() {
      return this.in.getNextMerge();
    }

    @Override
    public void onMergeFinished(MergePolicy.OneMerge merge) {
      this.in.onMergeFinished(merge);
    }

    @Override
    public boolean hasPendingMerges() {
      return this.in.hasPendingMerges();
    }

    @Override
    public void merge(MergePolicy.OneMerge merge) throws IOException {
      this.in.merge(merge);
    }
  }

  /**
   * A thin-wrapped scheduler passed to each index: e.g., one index has one instance of this class.
   * But under-the-hood, it dispatches all operations to the global
   * InstrumentedConcurrentMergeScheduler, except for the close(), that only waits for the
   * corresponding index's ongoing merges to finish.
   */
  public static class PerIndexPartitionMergeScheduler extends MergeScheduler {
    public InstrumentedConcurrentMergeScheduler getIn() {
      return this.in;
    }

    private final InstrumentedConcurrentMergeScheduler in;
    private final IndexPartitionIdentifier indexPartitionIdentifier;

    /** Creates a merge scheduler for a specific index partition. */
    public PerIndexPartitionMergeScheduler(
        InstrumentedConcurrentMergeScheduler in,
        IndexPartitionIdentifier indexPartitionIdentifier) {
      this.in = in;
      this.indexPartitionIdentifier = indexPartitionIdentifier;
    }

    @Override
    public void merge(MergeSource mergeSource, MergeTrigger trigger) throws IOException {
      var taggedMergeSource = new TaggedMergeSource(mergeSource, this.indexPartitionIdentifier);
      this.in.merge(taggedMergeSource, trigger);
    }

    @Override
    public Directory wrapForMerge(MergePolicy.OneMerge merge, Directory in) {
      return this.in.wrapForMerge(merge, in);
    }

    @Override
    public void close() throws IOException {
      this.in.close(this.indexPartitionIdentifier);
    }

    // We created this method because we cannot easily override the initialize() method
    // in ConcurrentMergeScheduler. We don't need the initDynamicDefaults() part in the
    // initialize() method, and only need the setInfoStream().
    public void setInfoStream(InfoStream infoStream) {
      this.in.setInfoStream(infoStream);
    }
  }

  private static record SegmentSize(String name, long size) {}

  private final MetricsFactory metricsFactory;
  private final Counter numMerges;
  private final AtomicLong runningMerges;
  // It tells the number of current merging documents, which also includes deleted documents.
  private final AtomicLong mergingDocs;
  private final Counter numSegmentsMerged;
  private final DistributionSummary mergeSize;
  private final DistributionSummary mergeResultSize;
  private final DistributionSummary mergedDocs;
  private final Timer mergeTime;
  // If our GenerationId is the only reference back to a particular index it is fine to GC.
  private final WeakHashMap<GenerationId, MergeStopwatch> mergeElapsedStopwatches;

  // Creates a per index-partition merge scheduler where input 'idx' tags the merge threads that
  // belong to a particular index-partition. The output PerIndexMergeScheduler wraps the running
  // instance of InstrumentedConcurrentMergeScheduler, and only one of its type exists per
  // index-partition.
  public PerIndexPartitionMergeScheduler createForIndexPartition(
      GenerationId generationId, int indexPartitionId, int numIndexes) {
    Optional<Integer> optionalIndexPartitionId =
        numIndexes > 1 ? Optional.of(indexPartitionId) : Optional.empty();
    return new PerIndexPartitionMergeScheduler(
        this, new IndexPartitionIdentifier(generationId, optionalIndexPartitionId));
  }

  /** Creates a new instrumented merge scheduler backed by the given meter registry. */
  public InstrumentedConcurrentMergeScheduler(MeterRegistry meterRegistry) {
    super();

    this.metricsFactory = new MetricsFactory("mergeScheduler", meterRegistry);
    var luceneTag = ServerStatusDataExtractor.Scope.LUCENE.getTag();
    this.numMerges =
        this.metricsFactory.counter(LuceneMeterData.NUM_MERGES_KEY, Tags.of(luceneTag));
    this.runningMerges = this.metricsFactory.numGauge("currentlyRunningMerges", Tags.of(luceneTag));
    this.mergingDocs = this.metricsFactory.numGauge("currentlyMergingDocs", Tags.of(luceneTag));
    this.numSegmentsMerged =
        this.metricsFactory.counter(LuceneMeterData.NUM_SEGMENTS_MERGED_KEY, Tags.of(luceneTag));
    this.mergedDocs =
        this.metricsFactory.summary(LuceneMeterData.MERGED_DOCS_KEY, Tags.of(luceneTag));
    this.mergeTime =
        this.metricsFactory.timer(LuceneMeterData.SEGMENT_MERGE_TIME_KEY, Tags.of(luceneTag));
    this.mergeSize =
        this.metricsFactory.summary(
            LuceneMeterData.MERGE_SIZE_KEY, Tags.of(luceneTag), 0.5, 0.75, 0.9, 0.99);
    this.mergeResultSize =
        this.metricsFactory.summary(
            LuceneMeterData.MERGE_RESULT_SIZE_KEY, Tags.of(luceneTag), 0.5, 0.75, 0.9, 0.99);
    this.mergeElapsedStopwatches = new WeakHashMap<>();
  }

  // Same functionality as calling initialize() in MergeScheduler, used by PerIndexMergeScheduler to
  // initialize the wrapped per index InstrumentedConcurrentMergeScheduler
  public void setInfoStream(InfoStream infoStream) {
    this.infoStream = infoStream;
  }

  /**
   * Closes all merge threads associated with the given index partition.
   *
   * <p>The implementation is copied from sync() in ConcurrentMergeScheduler in Lucene code, with
   * only one additional check:
   * ((InstrumentedMergeThread) t).getIndexPartitionIdentifier().equals(indexPartitionIdentifier).
   */
  public void close(IndexPartitionIdentifier indexPartitionIdentifier) {
    @Var boolean interrupted = false;
    try {
      while (true) {
        @Var MergeThread toSync = null;
        synchronized (this) {
          for (MergeThread t : this.mergeThreads) {
            // In case a merge thread is calling us, don't try to sync on
            // itself, since that will never finish!
            if (t.isAlive()
                && t != Thread.currentThread()
                // Only wait for merge threads for the current index to finish
                && ((InstrumentedMergeThread) t)
                    .getIndexPartitionIdentifier()
                    .equals(indexPartitionIdentifier)) {
              toSync = t;
              break;
            }
          }
        }
        if (toSync != null) {
          try {
            toSync.join();
          } catch (InterruptedException ie) {
            // ignore this Exception, we will retry until all threads are dead
            interrupted = true;
          }
        } else {
          break;
        }
      }
    } finally {
      // finally, restore interrupt status:
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  protected synchronized ConcurrentMergeScheduler.MergeThread getMergeThread(
      MergeSource mergeSource, MergePolicy.OneMerge merge) {
    String threadNamePrefix = "Lucene Merge Thread #" + this.mergeThreadCount++;
    var taggedMergeSource = (TaggedMergeSource) mergeSource;
    IndexPartitionIdentifier indexPartitionIdentifier =
        taggedMergeSource.getIndexPartitionIdentifier();
    ConcurrentMergeScheduler.MergeThread thread =
        new InstrumentedMergeThread(mergeSource, merge, indexPartitionIdentifier);
    thread.setDaemon(true);
    thread.setName(threadNamePrefix + " " + indexPartitionIdentifier.toString());
    return thread;
  }

  // XXX consider mapping by the merge itself and taking the max.

  /** A Stopwatch wrapper that can be used as an object for a gauge export. */
  private static class MergeStopwatch {
    private final Map<MergePolicy.OneMerge, Stopwatch> runningMerges = new HashMap<>();

    public synchronized double elapsedSeconds() {
      return this.runningMerges.values().stream()
          .mapToLong(s -> s.elapsed(TimeUnit.SECONDS))
          .max()
          .orElse(0L);
    }

    public synchronized void startOneMerge(MergePolicy.OneMerge merge) {
      this.runningMerges.computeIfAbsent(merge, unused -> Stopwatch.createStarted());
    }

    public synchronized void stopOneMerge(MergePolicy.OneMerge merge) {
      this.runningMerges.remove(merge);
    }
  }

  private MergeStopwatch getMergeStopwatch(IndexPartitionIdentifier indexPartitionIdentifier) {
    synchronized (this.mergeElapsedStopwatches) {
      // Find the stopwatch for the current index, or create a new stopwatch and a gauge for export
      // if not present.
      var mergeStopwatch =
          this.mergeElapsedStopwatches.computeIfAbsent(
              indexPartitionIdentifier.getGenerationId(),
              genId -> {
                var stopwatch = new MergeStopwatch();
                this.metricsFactory.objectValueGauge(
                    "mergeElapsedSeconds",
                    stopwatch,
                    MergeStopwatch::elapsedSeconds,
                    Tags.of("generationId logString", genId.uniqueString()));
                return stopwatch;
              });
      return mergeStopwatch;
    }
  }

  private class InstrumentedMergeThread extends MergeThread {

    private final MergePolicy.OneMerge merge;
    private final IndexPartitionIdentifier indexPartitionIdentifier;
    private static final Logger LOG = LoggerFactory.getLogger(InstrumentedMergeThread.class);

    public IndexPartitionIdentifier getIndexPartitionIdentifier() {
      return this.indexPartitionIdentifier;
    }

    private InstrumentedMergeThread(
        MergeSource mergeSource,
        MergePolicy.OneMerge merge,
        IndexPartitionIdentifier indexPartitionIdentifier) {
      super(mergeSource, merge);
      this.merge = merge;
      this.indexPartitionIdentifier = indexPartitionIdentifier;
    }

    @Override
    @SuppressWarnings("DoNotCall") // allow calling Thread.run()
    public void run() {

      // Log input segments before merge
      List<SegmentSize> inputSegments = new ArrayList<>();
      for (SegmentCommitInfo info : this.merge.segments) {
        @Var long size;
        try {
          size = info.sizeInBytes();
        } catch (IOException e) {
          size = -1L; // Use -1 if unable to read size
        }
        inputSegments.add(new SegmentSize(info.info.name, size));
      }
      LOG.atDebug()
          .addKeyValue("indexId", this.indexPartitionIdentifier.getGenerationId().indexId)
          .addKeyValue("inputSegments", inputSegments)
          .log("[Merge Start]");
      InstrumentedConcurrentMergeScheduler.this.numMerges.increment();
      InstrumentedConcurrentMergeScheduler.this.numSegmentsMerged.increment(
          this.merge.segments.size());
      InstrumentedConcurrentMergeScheduler.this.mergeSize.record(this.merge.totalBytesSize());
      InstrumentedConcurrentMergeScheduler.this.mergedDocs.record(this.merge.totalNumDocs());

      InstrumentedConcurrentMergeScheduler.this.runningMerges.incrementAndGet();
      InstrumentedConcurrentMergeScheduler.this.mergingDocs.addAndGet(this.merge.totalNumDocs());
      MergeStopwatch stopwatch =
          InstrumentedConcurrentMergeScheduler.this.getMergeStopwatch(
              this.indexPartitionIdentifier);
      stopwatch.startOneMerge(this.merge);

      try {
        Timed.runnable(InstrumentedConcurrentMergeScheduler.this.mergeTime, super::run);
      } catch (Exception e) {
        LOG.atWarn()
            .addKeyValue("indexId", this.indexPartitionIdentifier.getGenerationId().indexId)
            .addKeyValue("exceptionMessage", e.getMessage())
            .log("Exception during merge");
      } finally {
        stopwatch.stopOneMerge(this.merge);
        // Decrease the counters no matter run() throws error or not to guarantee the correctness.
        InstrumentedConcurrentMergeScheduler.this.runningMerges.decrementAndGet();
        InstrumentedConcurrentMergeScheduler.this.mergingDocs.addAndGet(-this.merge.totalNumDocs());
      }

      try {
        var commitInfo = this.merge.getMergeInfo();
        if (commitInfo != null) {
          InstrumentedConcurrentMergeScheduler.this.mergeResultSize.record(
              commitInfo.sizeInBytes());
          // Log the details of the output segment after the merge
          LOG.atDebug()
              .addKeyValue("indexId", this.indexPartitionIdentifier.getGenerationId().indexId)
              .addKeyValue("outputSegmentName", commitInfo.info.name)
              .addKeyValue("outputSegmentSize", commitInfo.sizeInBytes())
              .addKeyValue("inputSegments", inputSegments)
              .log("[Merge End]");
        }
      } catch (Exception e) {
        // ok to ignore as this is expected only in case of IO errors or merge abort
      }
    }
  }
}
