package com.xgen.mongot.embedding;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AutoEmbeddingMemoryBudgetTest {

  @Test
  public void testUnboundedBudgetAlwaysAcquires() {
    AutoEmbeddingMemoryBudget budget = AutoEmbeddingMemoryBudget.createDefault();

    assertThat(budget.tryAcquire(Long.MAX_VALUE)).isTrue();
    assertThat(budget.tryAcquire(Long.MAX_VALUE)).isTrue();
    assertThat(budget.tryAcquire(1_000_000_000L)).isTrue();
  }

  @Test
  public void testUnboundedBudgetReleaseIsNoOp() {
    AutoEmbeddingMemoryBudget budget = AutoEmbeddingMemoryBudget.createDefault();

    budget.release(Long.MAX_VALUE);
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(0);
  }

  @Test
  public void testBoundedBudgetAllowsAcquisitionWithinLimit() {
    AutoEmbeddingMemoryBudget budget = new AutoEmbeddingMemoryBudget(1000, false);

    assertThat(budget.tryAcquire(500)).isTrue();
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(500);

    assertThat(budget.tryAcquire(500)).isTrue();
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(1000);
  }

  @Test
  public void testBoundedBudgetRejectsWhenLimitExceeded() {
    AutoEmbeddingMemoryBudget budget = new AutoEmbeddingMemoryBudget(1000, false);

    assertThat(budget.tryAcquire(600)).isTrue();
    // 600 + 500 = 1100 > 1000 — should fail
    assertThat(budget.tryAcquire(500)).isFalse();
    // Usage must be rolled back on failure
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(600);
  }

  @Test
  public void testBoundedBudgetRejectsExactlyAtLimit() {
    AutoEmbeddingMemoryBudget budget = new AutoEmbeddingMemoryBudget(1000, false);

    assertThat(budget.tryAcquire(1000)).isTrue();
    // Budget is exactly full; any further acquisition must fail
    assertThat(budget.tryAcquire(1)).isFalse();
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(1000);
  }

  @Test
  public void testBoundedBudgetAvailableAgainAfterRelease() {
    AutoEmbeddingMemoryBudget budget = new AutoEmbeddingMemoryBudget(1000, false);

    assertThat(budget.tryAcquire(1000)).isTrue();
    assertThat(budget.tryAcquire(1)).isFalse();

    budget.release(1000);
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(0);
    assertThat(budget.tryAcquire(1000)).isTrue();
  }

  @Test
  public void testBoundedBudgetPartialRelease() {
    AutoEmbeddingMemoryBudget budget = new AutoEmbeddingMemoryBudget(1000, false);

    assertThat(budget.tryAcquire(800)).isTrue();
    // Can't fit 300 more (800 + 300 = 1100 > 1000)
    assertThat(budget.tryAcquire(300)).isFalse();

    budget.release(200);
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(600);
    // Now 600 + 300 = 900 <= 1000 — should succeed
    assertThat(budget.tryAcquire(300)).isTrue();
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(900);
  }

  @Test
  public void testConcurrentAcquisitionsDoNotExceedLimit() throws InterruptedException {
    long limit = 1000;
    AutoEmbeddingMemoryBudget budget = new AutoEmbeddingMemoryBudget(limit, false);
    int threadCount = 20;
    long requestPerThread = 100; // 20 * 100 = 2000 total requested, limit is 1000

    ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Future<?>> futures = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                try {
                  go.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                if (budget.tryAcquire(requestPerThread)) {
                  successCount.incrementAndGet();
                }
              }));
    }

    ready.await();
    go.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

    // Exactly limit / requestPerThread acquisitions should have succeeded
    assertThat(successCount.get()).isEqualTo((int) (limit / requestPerThread));
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(limit);
  }

  @Test
  public void testConcurrentAcquisitionsNoFalseRejections() throws InterruptedException {
    // All threads together fit exactly within the budget — every acquisition must succeed.
    int threadCount = 10;
    long requestPerThread = 100;
    long limit = (long) threadCount * requestPerThread; // 1000 — fits exactly
    AutoEmbeddingMemoryBudget budget = new AutoEmbeddingMemoryBudget(limit, false);

    ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Future<?>> futures = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                try {
                  go.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                if (budget.tryAcquire(requestPerThread)) {
                  successCount.incrementAndGet();
                }
              }));
    }

    ready.await();
    go.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

    // All acquisitions must succeed — no false rejections.
    assertThat(successCount.get()).isEqualTo(threadCount);
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(limit);
  }

  @Test
  public void testTryAcquireRejectsNegativeBytes() {
    AutoEmbeddingMemoryBudget budget = new AutoEmbeddingMemoryBudget(1000, false);
    assertThrows(IllegalArgumentException.class, () -> budget.tryAcquire(-1));
    // Budget must be unchanged after the rejection.
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(0);
  }

  @Test
  public void testReleaseRejectsNegativeBytes() {
    AutoEmbeddingMemoryBudget budget = new AutoEmbeddingMemoryBudget(1000, false);
    budget.tryAcquire(500);
    assertThrows(IllegalArgumentException.class, () -> budget.release(-1));
    // Usage must be unchanged after the rejection.
    assertThat(budget.getCurrentUsageBytes()).isEqualTo(500);
  }

  @Test
  public void testUnboundedTryAcquireRejectsNegativeBytes() {
    AutoEmbeddingMemoryBudget budget = AutoEmbeddingMemoryBudget.createDefault();
    assertThrows(IllegalArgumentException.class, () -> budget.tryAcquire(-1));
  }
}
