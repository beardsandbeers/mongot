package com.xgen.mongot.index.lucene;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableSet;
import com.xgen.mongot.index.lucene.backing.DiskStats;
import com.xgen.mongot.index.lucene.backing.IndexBackingStrategy;
import com.xgen.mongot.index.lucene.codec.LuceneCodec;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.util.AtomicDirectoryRemover;
import com.xgen.mongot.util.DirectorySize;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.mock.index.SearchIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.store.FSDirectory;
import org.junit.Before;
import org.junit.Test;

public class DiskIndexBackingStrategyTest {

  private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(1);
  private static final ScheduledExecutorService REFRESH_EXECUTOR =
      mock(ScheduledExecutorService.class);
  private static final int NUM_PARTITIONS = 4;
  private static final int NUM_DOCS = 3;

  private Path indexDir;

  private AtomicDirectoryRemover atomicDirectoryRemover;

  @Before
  public void setup() throws IOException {
    this.indexDir = TestUtils.getTempFolderPath();
    List<Path> partitions =
        List.of(
            this.indexDir.resolve("3"),
            this.indexDir.resolve("5"), // Looks like partition ID, but is out of range
            this.indexDir.resolve("abc"), // Looks like hex partition ID, but is out of range
            this.indexDir.resolve("0"),
            this.indexDir.resolve("1"),
            this.indexDir.resolve("meta"));

    for (Path partitionDir : partitions) {
      IndexWriterConfig config =
          new IndexWriterConfig()
              .setCodec(new LuceneCodec())
              .setMergePolicy(NoMergePolicy.INSTANCE)
              .setCommitOnClose(true);

      try (FSDirectory directory = FSDirectory.open(partitionDir);
          IndexWriter writer = new IndexWriter(directory, config)) {
        for (int i = 0; i < NUM_DOCS; ++i) {
          Document doc = new Document();
          doc.add(
              new StringField(FieldName.MetaField.ID.getLuceneFieldName(), "doc_" + i, Store.YES));
          writer.addDocument(doc);
          writer.flush();
        }
      }
    }
    Path trash = this.indexDir.resolveSibling(UUID.randomUUID().toString());
    this.atomicDirectoryRemover = new AtomicDirectoryRemover(trash);
  }

  @Test
  public void getDiskStats_partitionedIndex_returnsNonZero() {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);
    long expectedTotalSize = DirectorySize.of(this.indexDir.toFile());

    DiskStats diskStats = strat.getDiskStats();

    assertThat(diskStats.largestFileByteSize()).isGreaterThan(0);
    assertThat(diskStats.largestFileByteSize()).isAtMost(expectedTotalSize / NUM_PARTITIONS);
    assertThat(diskStats.totalFileByteSize()).isEqualTo(expectedTotalSize);
    assertThat(diskStats.totalFileByteSize()).isAtLeast(diskStats.largestFileByteSize());
    assertThat(diskStats.numFiles()).isAtLeast(NUM_PARTITIONS * NUM_DOCS);
  }

  @Test
  public void getDiskStats_unpartitionedIndex_returnsNonZero() {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir.resolve("0"),
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    DiskStats diskStats = strat.getDiskStats();

    assertThat(diskStats.largestFileByteSize()).isGreaterThan(0);
    assertThat(diskStats.totalFileByteSize()).isGreaterThan(0);
    assertThat(diskStats.totalFileByteSize()).isAtLeast(diskStats.largestFileByteSize());
    assertThat(diskStats.numFiles()).isAtLeast(NUM_DOCS);
  }

  @Test
  public void getDiskStats_indexClosed_returnsZero() throws Exception {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    strat.releaseResources();
    DiskStats diskStats = strat.getDiskStats();

    assertThat(diskStats.largestFileByteSize()).isEqualTo(0);
    assertThat(diskStats.numFiles()).isEqualTo(0);
    assertThat(diskStats.totalFileByteSize()).isEqualTo(0);
  }

  @Test
  public void getIndexSizeForIndexPartition_indexClosed_returnsZero() throws Exception {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    strat.releaseResources();

    for (int i = 0; i < NUM_PARTITIONS; ++i) {
      long size = strat.getIndexSizeForIndexPartition(i);
      assertWithMessage("Partition %s should be empty", i).that(size).isEqualTo(0);
    }
  }

  @Test
  public void getIndexSizeForIndexPartition_validPartition_returnsTotalSize() {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    long partition0 = strat.getIndexSizeForIndexPartition(0);
    long partition1 = strat.getIndexSizeForIndexPartition(1);
    long partition3 = strat.getIndexSizeForIndexPartition(3);

    assertThat(partition0).isEqualTo(DirectorySize.of(this.indexDir.resolve("0").toFile()));
    assertThat(partition1).isEqualTo(DirectorySize.of(this.indexDir.resolve("1").toFile()));
    assertThat(partition3).isEqualTo(DirectorySize.of(this.indexDir.resolve("3").toFile()));
  }

  @Test
  public void getIndexSizeForIndexPartition_emptyPartition_returnsZero() {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    long partition2 = strat.getIndexSizeForIndexPartition(2);

    assertThat(partition2).isEqualTo(0);
  }

  @Test
  public void getIndexSizeForIndexPartition_allPartitions_areLessThanTotalSize() {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    DiskStats diskStats = strat.getDiskStats();
    long partition0 = strat.getIndexSizeForIndexPartition(0);
    long partition1 = strat.getIndexSizeForIndexPartition(1);
    long partition2 = strat.getIndexSizeForIndexPartition(2);
    long partition3 = strat.getIndexSizeForIndexPartition(3);

    long totalSize = partition0 + partition1 + partition2 + partition3;
    assertThat(ImmutableSet.of(partition0, partition1, partition3)).hasSize(1);
    assertThat(totalSize).isAtMost(diskStats.totalFileByteSize());
  }

  @Test
  public void getIndexSizeForIndexPartition_invalidPartition_returnsZero() {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    long offByOnePartition = strat.getIndexSizeForIndexPartition(4);
    long invalidPartition = strat.getIndexSizeForIndexPartition(9001);
    long negativePartition = strat.getIndexSizeForIndexPartition(-1);

    assertThat(offByOnePartition).isEqualTo(0);
    assertThat(invalidPartition).isEqualTo(0);
    assertThat(negativePartition).isEqualTo(0);
  }

  @Test
  public void getIndexSizeForIndexPartition_invalidHexPartition_returnsZero() {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    long invalidPartition = strat.getIndexSizeForIndexPartition(Integer.parseInt("abc", 16));

    assertThat(invalidPartition).isEqualTo(0);
  }

  @Test
  public void getIndexSizeForIndexPartition_unpartitionedIndex_returnsZero() {
    Path unpartitionedIndexDir = this.indexDir.resolve("0");
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            unpartitionedIndexDir,
            unpartitionedIndexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            1);

    long invalidPartition = strat.getIndexSizeForIndexPartition(0);

    assertThat(invalidPartition).isEqualTo(0);
  }

  @Test
  public void releaseResources_openIndex_removesEntireDirectory() throws Exception {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    strat.releaseResources();

    assertFalse(this.indexDir.toFile().exists());
    assertFalse(this.indexDir.resolve("meta").toFile().exists());
  }

  @Test
  public void clearMetadata_openIndex_removesOnlyMetadata() throws Exception {
    IndexBackingStrategy strat =
        IndexBackingStrategyFactory.diskBacked(
            REFRESH_EXECUTOR,
            REFRESH_INTERVAL,
            this.atomicDirectoryRemover,
            this.indexDir,
            this.indexDir.resolve("meta"),
            SearchIndex.mockMetricsFactory(),
            NUM_PARTITIONS);

    strat.clearMetadata();

    assertTrue(this.indexDir.toFile().exists());
    assertThat(DirectorySize.of(this.indexDir.toFile())).isGreaterThan(0);
    assertThat(DirectorySize.of(this.indexDir.resolve("0").toFile())).isGreaterThan(0);
    // Metadata should be cleared, but the directory should still exist.
    assertTrue(this.indexDir.resolve("meta").toFile().exists());
    assertThat(DirectorySize.of(this.indexDir.resolve("meta").toFile())).isEqualTo(0);
  }
}
