package com.xgen.mongot.index.lucene;

import com.xgen.mongot.index.lucene.backing.IndexBackingStrategy;
import com.xgen.mongot.metrics.PerIndexMetricsFactory;
import com.xgen.mongot.util.AtomicDirectoryRemover;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

/** Factory for creating {@link IndexBackingStrategy} instances. */
public final class IndexBackingStrategyFactory {

  private IndexBackingStrategyFactory() {}

  /** Creates a disk-backed strategy. */
  public static IndexBackingStrategy diskBacked(
      ScheduledExecutorService refreshExecutor,
      Duration refreshInterval,
      AtomicDirectoryRemover directoryRemover,
      Path directoryPath,
      Path metadataDirectoryPath,
      PerIndexMetricsFactory metricsFactory,
      int numPartitions) {

    return new DiskIndexBackingStrategy(
        directoryPath,
        metricsFactory,
        refreshExecutor,
        refreshInterval,
        directoryRemover,
        metadataDirectoryPath,
        numPartitions);
  }
}
