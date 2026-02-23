package com.xgen.testing;

import static org.junit.Assert.assertThrows;

import com.google.common.util.concurrent.Uninterruptibles;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;

public class ControlledBlockingQueueTest {
  @Test
  public void testReadyDoesNotBlock() throws InterruptedException {
    ControlledBlockingQueue<Integer> queue = ControlledBlockingQueue.ready();
    queue.add(1);
    queue.take();
  }

  @Test
  public void testPausedWaits() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ControlledBlockingQueue<Integer> queue = ControlledBlockingQueue.paused();
    queue.add(1);

    Future<Integer> task = executor.submit(() -> Uninterruptibles.takeUninterruptibly(queue));

    assertThrows(TimeoutException.class, () -> task.get(1, TimeUnit.SECONDS));
  }

  @Test
  public void resumeAfterPause() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ControlledBlockingQueue<Integer> queue = ControlledBlockingQueue.ready();
    queue.add(1);
    queue.pause();

    Future<Integer> task = executor.submit(() -> Uninterruptibles.takeUninterruptibly(queue));
    queue.resume();

    task.get(5, TimeUnit.SECONDS);
  }

  @Test
  public void testResumeNotifiesAllThreads() throws Exception {
    ExecutorService executor = Executors.newCachedThreadPool();
    ControlledBlockingQueue<Integer> queue = ControlledBlockingQueue.paused();
    queue.add(1);
    queue.add(2);

    Future<Integer> task1 = executor.submit(() -> Uninterruptibles.takeUninterruptibly(queue));
    Future<Integer> task2 = executor.submit(() -> Uninterruptibles.takeUninterruptibly(queue));
    queue.resume();

    task1.get(5, TimeUnit.SECONDS);
    task2.get(5, TimeUnit.SECONDS);
  }

  @Test
  public void testWaitForItemsDequeuedTimeout() throws Exception {
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    ControlledBlockingQueue<Integer> queue = ControlledBlockingQueue.ready();
    queue.addAll(List.of(1, 2));

    Future<?> task =
        executor.submit(
            () -> {
              try {
                queue.waitForItemsDequeued(2);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });

    queue.take();
    assertThrows(TimeoutException.class, () -> task.get(500, TimeUnit.MILLISECONDS));
  }

  @Test
  public void testWaitForItemsDequeued() throws Exception {
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    ControlledBlockingQueue<Integer> queue = ControlledBlockingQueue.ready();
    queue.addAll(List.of(1, 2));

    Future<?> task =
        executor.submit(
            () -> {
              try {
                queue.waitForItemsDequeued(2);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });

    queue.take();
    queue.take();
    task.get(5, TimeUnit.SECONDS);
  }
}
