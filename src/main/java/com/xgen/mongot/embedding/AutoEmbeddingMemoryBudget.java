package com.xgen.mongot.embedding;

import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.Var;
import com.xgen.mongot.util.Check;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks global in-memory usage of auto-embedding batches across all indexes.
 *
 * <p>Uses acquire/release semantics to prevent concurrent indexing batches from collectively
 * exceeding the memory budget. When a batch cannot acquire memory, it is fast-failed with a
 * transient exception so it can be retried later when memory frees up.
 *
 * <p>The default budget is unbounded ({@link Long#MAX_VALUE}). This can be tuned by updating {@link
 * #GLOBAL_BUDGET_BYTES} in source and redeploying.
 */
public class AutoEmbeddingMemoryBudget {

  private static final FluentLogger FLOGGER = FluentLogger.forEnclosingClass();

  /**
   * Global auto-embedding memory budget in bytes, shared across all indexes on this mongot.
   * Unbounded by default. TODO(CLOUDP-387607): Support a configurable memory budget.
   */
  static final long GLOBAL_BUDGET_BYTES = Long.MAX_VALUE;

  private final long maxBytes;
  private final AtomicLong currentUsageBytes = new AtomicLong(0);
  private final boolean unbounded;

  public AutoEmbeddingMemoryBudget(long maxBytes, boolean unbounded) {
    this.maxBytes = maxBytes;
    this.unbounded = unbounded;
  }

  /** Creates a new budget using the value of {@link #GLOBAL_BUDGET_BYTES}. */
  public static AutoEmbeddingMemoryBudget createDefault() {
    return new AutoEmbeddingMemoryBudget(GLOBAL_BUDGET_BYTES, true);
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
