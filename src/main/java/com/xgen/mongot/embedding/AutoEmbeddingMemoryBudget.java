package com.xgen.mongot.embedding;

import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.Var;
import com.xgen.mongot.util.Check;
import com.xgen.mongot.util.Runtime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks global in-memory usage of auto-embedding batches across all indexes.
 *
 * <p>Uses acquire/release semantics to prevent concurrent indexing batches from collectively
 * exceeding the memory budget. When a batch cannot acquire memory, it is fast-failed with a
 * transient exception so it can be retried later when memory frees up.
 *
 * <p>The default budget is unbounded ({@link Long#MAX_VALUE}). The budget can be sized as a
 * percentage of JVM heap using {@link #fromHeapPercent}.
 */
public class AutoEmbeddingMemoryBudget {

  private static final FluentLogger FLOGGER = FluentLogger.forEnclosingClass();

  private final long maxBytes;
  private final AtomicLong currentUsageBytes = new AtomicLong(0);
  private final boolean unbounded;

  public AutoEmbeddingMemoryBudget(long maxBytes, boolean unbounded) {
    this.maxBytes = maxBytes;
    this.unbounded = unbounded;
  }

  /** Creates an unbounded budget (no memory limit enforced). */
  public static AutoEmbeddingMemoryBudget createDefault() {
    return new AutoEmbeddingMemoryBudget(Long.MAX_VALUE, true);
  }

  /**
   * Creates a budget sized to {@code heapPercent}% of the JVM's max heap.
   *
   * <p>When {@code heapPercent >= 100}, returns an unbounded budget so that operators can disable
   * the limit by setting 100%.
   *
   * @param heapPercent percentage of max heap to use as the budget (1–100 inclusive)
   * @param runtime runtime used to determine the JVM max heap size
   */
  public static AutoEmbeddingMemoryBudget fromHeapPercent(int heapPercent, Runtime runtime) {
    Check.checkArg(
        heapPercent >= 1 && heapPercent <= 100,
        "heapPercent must be between 1 and 100 (inclusive), got %s",
        heapPercent);
    if (heapPercent >= 100) {
      return createDefault();
    }
    long maxBytes = runtime.getMaxHeapSize().toBytes() * heapPercent / 100;
    FLOGGER.atInfo().log(
        "Auto-embedding memory budget set to %d%% of heap = %d bytes", heapPercent, maxBytes);
    return new AutoEmbeddingMemoryBudget(maxBytes, false);
  }

  /**
   * Attempts to acquire {@code bytes} from the budget. Thread-safe.
   *
   * @return {@code true} if acquired; {@code false} if the budget would be exceeded.
   */
  public boolean tryAcquire(long bytes) {
    Check.checkArg(bytes >= 0, "bytes must be non-negative: %s", bytes);
    if (this.unbounded) {
      return true;
    }
    @Var long current;
    do {
      current = this.currentUsageBytes.get();
      if (current > this.maxBytes - bytes) {
        FLOGGER.atWarning().atMostEvery(1, TimeUnit.MINUTES).log(
            "Global auto-embedding memory budget exceeded: current=%d bytes, requested=%d"
                + " bytes, limit=%d bytes",
            current, bytes, this.maxBytes);
        return false;
      }
    } while (!this.currentUsageBytes.compareAndSet(current, current + bytes));
    return true;
  }

  /** Releases previously acquired bytes back to the budget. */
  public void release(long bytes) {
    Check.checkArg(bytes >= 0, "bytes must be non-negative: %s", bytes);
    if (!this.unbounded) {
      this.currentUsageBytes.addAndGet(-bytes);
    }
  }

  public long getCurrentUsageBytes() {
    return this.currentUsageBytes.get();
  }
}
