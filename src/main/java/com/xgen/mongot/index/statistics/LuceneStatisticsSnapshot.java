package com.xgen.mongot.index.statistics;

import com.google.errorprone.annotations.Var;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.util.IOUtils;

/** Owns independently retained readers; no directory or field scan happens during acquisition. */
public final class LuceneStatisticsSnapshot implements Closeable {
  private final List<DirectoryReader> readers;
  private final List<Closeable> owners;
  private final long cacheHeapBytes;
  private final AtomicBoolean closed = new AtomicBoolean();

  private LuceneStatisticsSnapshot(
      List<DirectoryReader> readers, List<Closeable> owners, long cacheHeapBytes) {
    this.readers = List.copyOf(readers);
    this.owners = List.copyOf(owners);
    this.cacheHeapBytes = cacheHeapBytes;
  }

  public static LuceneStatisticsSnapshot retain(DirectoryReader reader, long cacheHeapBytes) {
    reader.incRef();
    return new LuceneStatisticsSnapshot(List.of(reader), List.of(reader::decRef), cacheHeapBytes);
  }

  /** Takes ownership of every supplied snapshot, including on failure. */
  public static LuceneStatisticsSnapshot combine(List<LuceneStatisticsSnapshot> snapshots) {
    var readers = new ArrayList<DirectoryReader>();
    @Var long cacheBytes = 0;
    try {
      for (var snapshot : snapshots) {
        readers.addAll(snapshot.readers());
        cacheBytes = Math.addExact(cacheBytes, snapshot.cacheHeapBytes());
      }
      return new LuceneStatisticsSnapshot(readers, new ArrayList<>(snapshots), cacheBytes);
    } catch (RuntimeException | Error e) {
      IOUtils.closeWhileHandlingException(snapshots);
      throw e;
    }
  }

  public List<DirectoryReader> readers() {
    if (this.closed.get()) {
      throw new IllegalStateException("Statistics snapshot is closed");
    }
    return this.readers;
  }

  public long cacheHeapBytes() {
    return this.cacheHeapBytes;
  }

  @Override
  public void close() throws IOException {
    if (this.closed.compareAndSet(false, true)) {
      IOUtils.close(this.owners);
    }
  }
}
