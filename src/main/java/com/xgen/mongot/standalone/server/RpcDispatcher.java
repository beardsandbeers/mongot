package com.xgen.mongot.standalone.server;

import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.metrics.StandaloneMetrics;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/** Bounded work on dedicated workers; synchronous indexing does not occupy Netty threads. */
final class RpcDispatcher implements AutoCloseable {
  private final ThreadPoolExecutor executor;
  private final Semaphore requests;
  private final AtomicLong bytes = new AtomicLong();
  private final long maxBytes;
  private final StandaloneMetrics metrics;

  RpcDispatcher(StandaloneConfig config, StandaloneMetrics metrics) {
    this.metrics = metrics;
    this.requests = new Semaphore(config.maxInFlightRequests());
    this.maxBytes = config.maxInFlightBytes();
    int threads = Math.min(8, config.maxInFlightRequests());
    this.executor =
        new ThreadPoolExecutor(
            threads,
            threads,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(config.maxInFlightRequests()),
            Thread.ofPlatform().name("standalone-rpc-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    metrics.gauge(
        "rpc.inflight",
        this.requests,
        permits -> config.maxInFlightRequests() - permits.availablePermits());
    metrics.gauge("rpc.bytes", this.bytes, AtomicLong::doubleValue);
    metrics.gauge("rpc.queued", this.executor, pool -> pool.getQueue().size());
  }

  <T> void unary(long size, StreamObserver<T> observer, Supplier<T> action) {
    async(size, observer, () -> CompletableFuture.completedFuture(action.get()));
  }

  <T> void unary(
      long size,
      StreamObserver<T> observer,
      Supplier<T> action,
      java.util.function.Consumer<T> abandoned) {
    async(size, observer, () -> CompletableFuture.completedFuture(action.get()), abandoned);
  }

  <T> void async(long size, StreamObserver<T> observer, Supplier<CompletableFuture<T>> action) {
    async(size, observer, action, unused -> {});
  }

  private <T> void async(
      long size,
      StreamObserver<T> observer,
      Supplier<CompletableFuture<T>> action,
      java.util.function.Consumer<T> abandoned) {
    long started = System.nanoTime();
    String operation = StandaloneAuthInterceptor.operation();
    if (size > this.maxBytes || !this.requests.tryAcquire()) {
      this.metrics.count("rpc.rejected", 1, "stage", "requests");
      observer.onError(
          Status.RESOURCE_EXHAUSTED
              .withDescription("RPC admission capacity is full")
              .asRuntimeException());
      return;
    }
    if (this.bytes.addAndGet(size) > this.maxBytes) {
      this.metrics.count("rpc.rejected", 1, "stage", "bytes");
      this.bytes.addAndGet(-size);
      this.requests.release();
      observer.onError(
          Status.RESOURCE_EXHAUSTED
              .withDescription("RPC byte capacity is full")
              .asRuntimeException());
      return;
    }
    var context = Context.current();
    var completed = new AtomicBoolean();
    var pending = new AtomicReference<Optional<CompletableFuture<T>>>(Optional.empty());
    Context.CancellationListener cancellation =
        cancelled -> pending.get().ifPresent(future -> future.cancel(false));
    context.addListener(cancellation, Runnable::run);
    java.util.function.BiConsumer<@Nullable T, @Nullable Throwable> finish =
        (value, error) -> {
          if (!completed.compareAndSet(false, true)) {
            return;
          }
          context.removeListener(cancellation);
          try {
            if (context.isCancelled()) {
              if (value != null) {
                abandoned.accept(value);
              }
              observer.onError(
                  Optional.ofNullable(Contexts.statusFromCancelled(context))
                      .orElse(Status.CANCELLED)
                      .asRuntimeException());
            } else if (error != null) {
              observer.onError(status(error).asRuntimeException());
            } else {
              observer.onNext(java.util.Objects.requireNonNull(value));
              observer.onCompleted();
            }
          } catch (RuntimeException deliveryFailure) {
            if (value != null) {
              abandoned.accept(value);
            }
            throw deliveryFailure;
          } finally {
            String status =
                context.isCancelled()
                    ? Optional.ofNullable(Contexts.statusFromCancelled(context))
                        .orElse(Status.CANCELLED)
                        .getCode()
                        .name()
                    : error == null ? "OK" : status(error).getCode().name();
            this.metrics.elapsed("rpc.duration", started, "operation", operation, "status", status);
            this.metrics.count("rpc.completed", 1, "operation", operation, "status", status);
            this.bytes.addAndGet(-size);
            this.requests.release();
          }
        };
    try {
      this.executor.execute(
          context.wrap(
              () -> {
                try {
                  if (context.isCancelled()) {
                    finish.accept(null, Status.CANCELLED.asRuntimeException());
                    return;
                  }
                  var future = action.get();
                  long waitStarted = System.nanoTime();
                  future.whenComplete(
                      (ignored, error) ->
                          this.metrics.elapsed(
                              "rpc.async.wait", waitStarted, "operation", operation));
                  pending.set(Optional.of(future));
                  future.whenComplete(finish);
                  if (context.isCancelled()) {
                    future.cancel(false);
                  }
                } catch (RuntimeException e) {
                  finish.accept(null, e);
                }
              }));
    } catch (RejectedExecutionException e) {
      finish.accept(
          null,
          Status.RESOURCE_EXHAUSTED
              .withDescription("RPC worker queue is full")
              .asRuntimeException());
    }
  }

  private static Status status(Throwable error) {
    Throwable cause =
        error instanceof java.util.concurrent.CompletionException && error.getCause() != null
            ? error.getCause()
            : error;
    if (cause instanceof io.grpc.StatusRuntimeException exception) {
      return exception.getStatus();
    }
    if (cause instanceof IllegalArgumentException) {
      return Status.INVALID_ARGUMENT.withDescription(cause.getMessage());
    }
    return Status.INTERNAL.withDescription("Standalone request failed").withCause(cause);
  }

  @Override
  public void close() throws InterruptedException {
    this.executor.shutdown();
    if (!this.executor.awaitTermination(30, TimeUnit.SECONDS)) {
      this.executor.shutdownNow();
      if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Standalone RPC workers did not stop");
      }
    }
  }
}
