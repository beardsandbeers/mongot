package com.xgen.mongot.index.lucene;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.xgen.mongot.index.lucene.backing.IndexBackingStrategy;
import com.xgen.mongot.index.lucene.directory.IndexPathFactory;
import com.xgen.mongot.index.status.IndexStatus;
import com.xgen.mongot.metrics.PerIndexMetricsFactory;
import com.xgen.mongot.util.AtomicDirectoryRemover;
import com.xgen.mongot.util.DirectorySize;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.lucene.search.ReferenceManager;

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
      PerIndexMetricsFactory metricsFactory) {

    class LargestFileVisitor implements FileVisitor<Path> {
      public long largestFileByteSize = 0L;

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        this.largestFileByteSize = Math.max(this.largestFileByteSize, attrs.size());
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        // Ignore errors when visiting files.
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
      }
    }

    return new IndexBackingStrategy() {

      @GuardedBy("this")
      private boolean released;

      @Override
      public Closeable createIndexRefresher(
          Supplier<IndexStatus> statusRef, ImmutableList<ReferenceManager<?>> searcherManagers) {
        var refresherMetricsFactory = metricsFactory.childMetricsFactory("luceneIndexRefresher");
        return new PeriodicLuceneIndexRefresher(
            refreshExecutor, refreshInterval, searcherManagers, statusRef, refresherMetricsFactory);
      }

      @Override
      public synchronized void releaseResources() throws IOException {
        this.released = true;
        directoryRemover.deleteDirectory(directoryPath);
        directoryRemover.deleteDirectory(metadataDirectoryPath);
      }

      @Override
      public long getIndexSize() {
        return getIndexSizeFromPath(directoryPath);
      }

      @Override
      public long getLargestIndexFileSize() {
        return getLargestFileFromPath(directoryPath);
      }

      @Override
      public long getNumFilesInIndex() {
        return getNumFilesAtPath(directoryPath);
      }

      @Override
      public long getIndexSizeForIndexPartition(int indexPartitionId) {
        return getIndexSizeFromPath(
            directoryPath.resolve(
                IndexPathFactory.getIndexPartitionRelativePath(indexPartitionId)));
      }

      @Override
      public synchronized void clearMetadata() throws IOException {
        directoryRemover.deleteFilesInDirectory(metadataDirectoryPath);
      }

      private synchronized long getIndexSizeFromPath(Path path) {
        if (this.released) {
          return 0L;
        }

        return DirectorySize.of(path.toFile());
      }

      private synchronized long getLargestFileFromPath(Path path) {
        if (this.released) {
          return 0L;
        }

        var visitor = new LargestFileVisitor();
        try {
          Files.walkFileTree(path, visitor);
          return visitor.largestFileByteSize;
        } catch (IOException e) {
          // Report 0 in this case. walkFileTree() should handle errors during listing and forward
          // them to the visitor, which will just tell it to continue.
          return 0L;
        }
      }

      private synchronized long getNumFilesAtPath(Path path) {
        if (this.released) {
          return 0;
        }

        try (Stream<Path> pathStream = Files.walk(path)) {
          return pathStream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
          // Report 0 in this case. walkFileTree() should handle errors during listing and forward
          // them to the visitor, which will just tell it to continue.
          return 0;
        }
      }
    };
  }
}
