package com.xgen.mongot.standalone.runtime;

import com.xgen.mongot.monitor.DiskMonitor;
import com.xgen.mongot.monitor.Gate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Local disk gating; never requests a MongoDB replication restart or discards accepted data. */
final class StandaloneDiskMonitor implements DiskMonitor {
  private final Path path;
  private final CopyOnWriteArrayList<Gate> gates = new CopyOnWriteArrayList<>();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("standalone-disk-monitor").factory());
  private volatile double utilization = 1;

  StandaloneDiskMonitor(Path path) {
    this.path = path;
    sample();
  }

  @Override
  public void register(Gate gate) {
    this.gates.add(gate);
    gate.update(this.utilization);
  }

  @Override
  public void start(Duration interval) {
    this.executor.scheduleWithFixedDelay(
        this::sample, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void sample() {
    try {
      var volume = Files.getFileStore(this.path);
      this.utilization = 1.0 - (double) volume.getUsableSpace() / volume.getTotalSpace();
    } catch (IOException e) {
      this.utilization = 1;
    }
    this.gates.forEach(gate -> gate.update(this.utilization));
  }

  @Override
  public void stop() {
    this.executor.shutdown();
    try {
      if (!this.executor.awaitTermination(10, TimeUnit.SECONDS)) {
        this.executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      this.executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
