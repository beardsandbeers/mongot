package com.xgen.mongot.standalone.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/** Bounded service metrics: labels are operation/type/status, never request or document IDs. */
public final class StandaloneMetrics {
  private final Optional<MeterRegistry> registry;

  public StandaloneMetrics(MeterRegistry registry) {
    this.registry = Optional.of(registry);
  }

  private StandaloneMetrics() {
    this.registry = Optional.empty();
  }

  public static StandaloneMetrics disabled() {
    return new StandaloneMetrics();
  }

  public void count(String name, double amount, String... tags) {
    this.registry.ifPresent(value -> value.counter("standalone." + name, tags).increment(amount));
  }

  public void elapsed(String name, long startNanos, String... tags) {
    this.registry.ifPresent(
        value ->
            value
                .timer("standalone." + name, tags)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS));
  }

  public <T> void gauge(String name, T object, ToDoubleFunction<T> measurement) {
    this.registry.ifPresent(value -> value.gauge("standalone." + name, object, measurement));
  }

  public void retireGeneration(String generationId) {
    this.registry.ifPresent(
        value ->
            value.getMeters().stream()
                .filter(meter -> generationId.equals(meter.getId().getTag("generation")))
                .toList()
                .forEach(value::remove));
  }
}
