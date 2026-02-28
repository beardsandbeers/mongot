package com.xgen.mongot.index.lucene.init;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xgen.mongot.index.EncodedUserData;
import com.xgen.mongot.index.lucene.commit.LuceneCommitData;
import com.xgen.mongot.index.lucene.directory.IndexDirectoryFactory;
import com.xgen.mongot.index.lucene.searcher.LuceneSearcherManager;
import com.xgen.mongot.index.lucene.writer.MultiLuceneIndexWriter;
import com.xgen.mongot.index.lucene.writer.SingleLuceneIndexWriter;
import com.xgen.mongot.util.CheckedStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.lucene.store.Directory;
import org.junit.Assert;
import org.junit.Test;

public class LuceneIndexResourcesInitializerTest {

  public static class Mocks {
    public final IndexDirectoryFactory directoryFactory;
    public final List<Directory> directories;
    public final List<File> metadataDirectories;
    public final LuceneIndexResourcesInitializer.SingleLuceneIndexWriterCreator
        singleLuceneIndexWriterCreator;
    public final List<SingleLuceneIndexWriter> singleLuceneIndexWriters;
    public LuceneIndexResourcesInitializer.LuceneSearcherManagerCreator
        luceneSearcherManagerCreator;
    public List<LuceneSearcherManager> luceneSearcherManagers;
    public List<Closeable> closedResources;
    public Consumer<Closeable> resourceCloser;

    Mocks(int numPartitions) throws IOException {
      this.directoryFactory = mock(IndexDirectoryFactory.class);
      this.directories =
          IntStream.range(0, numPartitions)
              .mapToObj(unused -> mock(Directory.class))
              .collect(Collectors.toList());
      this.metadataDirectories =
          IntStream.range(0, numPartitions)
              .mapToObj(unused -> mock(File.class))
              .collect(Collectors.toList());
      this.singleLuceneIndexWriterCreator =
          mock(LuceneIndexResourcesInitializer.SingleLuceneIndexWriterCreator.class);
      this.singleLuceneIndexWriters =
          IntStream.range(0, numPartitions)
              .mapToObj(
                  unused -> {
                    var writer = mock(SingleLuceneIndexWriter.class);
                    when(writer.getInternalWriterData())
                        .thenReturn(LuceneCommitData.IndexWriterData.EMPTY);
                    when(writer.getCommitUserData()).thenReturn(EncodedUserData.EMPTY);
                    return writer;
                  })
              .collect(Collectors.toList());
      this.luceneSearcherManagerCreator =
          mock(LuceneIndexResourcesInitializer.LuceneSearcherManagerCreator.class);
      this.luceneSearcherManagers =
          IntStream.range(0, numPartitions)
              .mapToObj(unused -> mock(LuceneSearcherManager.class))
              .collect(Collectors.toList());
      this.closedResources = new ArrayList<>();
      this.resourceCloser = this.closedResources::add;
      for (int indexPartitionId = 0; indexPartitionId < numPartitions; ++indexPartitionId) {
        when(this.directoryFactory.create(indexPartitionId))
            .thenReturn(this.directories.get(indexPartitionId));
        when(this.directoryFactory.createMetadata(indexPartitionId))
            .thenReturn(this.metadataDirectories.get(indexPartitionId));
        when(this.singleLuceneIndexWriterCreator.create(
                this.directories.get(indexPartitionId), indexPartitionId))
            .thenReturn(this.singleLuceneIndexWriters.get(indexPartitionId));
        when(this.luceneSearcherManagerCreator.create(
                this.singleLuceneIndexWriters.get(indexPartitionId)))
            .thenReturn(this.luceneSearcherManagers.get(indexPartitionId));
      }
    }
  }

  @Test
  public void testOneIndexPartition() throws IOException {
    Mocks mocks = new Mocks(1);
    var indexResources =
        LuceneIndexResourcesInitializer.initialize(
            1,
            mocks.directoryFactory,
            mocks.singleLuceneIndexWriterCreator,
            mocks.luceneSearcherManagerCreator);
    Assert.assertEquals(mocks.directories, indexResources.directories);
    Assert.assertEquals(mocks.metadataDirectories, indexResources.metadataDirectories);
    Assert.assertEquals(mocks.singleLuceneIndexWriters.get(0), indexResources.luceneIndexWriter);
    Assert.assertEquals(mocks.luceneSearcherManagers, indexResources.luceneSearcherManagers);
  }

  @Test
  public void testTwoIndexPartitions() throws IOException {
    int numPartitions = 2;
    Mocks mocks = new Mocks(numPartitions);
    var indexResources =
        LuceneIndexResourcesInitializer.initialize(
            numPartitions,
            mocks.directoryFactory,
            mocks.singleLuceneIndexWriterCreator,
            mocks.luceneSearcherManagerCreator);
    Assert.assertEquals(mocks.directories, indexResources.directories);
    Assert.assertEquals(mocks.metadataDirectories, indexResources.metadataDirectories);
    assertThat(indexResources.luceneIndexWriter).isInstanceOf(MultiLuceneIndexWriter.class);
    Assert.assertEquals(
        mocks.singleLuceneIndexWriters,
        ((MultiLuceneIndexWriter) indexResources.luceneIndexWriter).getSingleLuceneIndexWriters());
    Assert.assertEquals(mocks.luceneSearcherManagers, indexResources.luceneSearcherManagers);
  }

  @Test
  public void testExceptionWhenCreatingDirectory() throws IOException {
    var numPartitions = 2;
    Mocks mocks = new Mocks(numPartitions);
    when(mocks.directoryFactory.create(1)).thenThrow(new IOException("Failed to create dir"));
    Assert.assertThrows(
        IOException.class,
        () ->
            LuceneIndexResourcesInitializer.initialize(
                numPartitions,
                mocks.directoryFactory,
                mocks.singleLuceneIndexWriterCreator,
                mocks.luceneSearcherManagerCreator,
                mocks.resourceCloser));
    // Resources should be closed in the proper order.
    Assert.assertEquals(
        List.of(mocks.singleLuceneIndexWriters.get(0), mocks.directories.get(0)),
        mocks.closedResources);
  }

  @Test
  public void testExceptionWhenCreatingSingleLuceneIndexWriter() throws IOException {
    var numPartitions = 2;
    Mocks mocks = new Mocks(numPartitions);
    when(mocks.singleLuceneIndexWriterCreator.create(mocks.directories.get(1), 1))
        .thenThrow(new IOException("Failed to create writer"));
    Assert.assertThrows(
        IOException.class,
        () ->
            LuceneIndexResourcesInitializer.initialize(
                numPartitions,
                mocks.directoryFactory,
                mocks.singleLuceneIndexWriterCreator,
                mocks.luceneSearcherManagerCreator,
                mocks.resourceCloser));
    // Resources should be closed in the proper order.
    Assert.assertEquals(
        List.of(
            mocks.singleLuceneIndexWriters.get(0),
            mocks.directories.get(0),
            mocks.directories.get(1)),
        mocks.closedResources);
  }

  @Test
  public void testExceptionWhenCreatingLuceneSearcherManagers() throws IOException {
    int numPartitions = 2;
    Mocks mocks = new Mocks(numPartitions);
    when(mocks.luceneSearcherManagerCreator.create(mocks.singleLuceneIndexWriters.get(1)))
        .thenThrow(new IOException("Failed to create search manager"));
    Assert.assertThrows(
        IOException.class,
        () ->
            LuceneIndexResourcesInitializer.initialize(
                numPartitions,
                mocks.directoryFactory,
                mocks.singleLuceneIndexWriterCreator,
                mocks.luceneSearcherManagerCreator,
                mocks.resourceCloser));
    // Resources should be closed in the proper order.
    Assert.assertEquals(
        List.of(
            mocks.luceneSearcherManagers.get(0),
            mocks.singleLuceneIndexWriters.get(0),
            mocks.singleLuceneIndexWriters.get(1),
            mocks.directories.get(0),
            mocks.directories.get(1)),
        mocks.closedResources);
  }

  @Test
  public void testAllPartitionsAreCleared() throws IOException {
    int numPartitions = 2;
    Mocks mocks = new Mocks(numPartitions);
    mocks.singleLuceneIndexWriters.forEach(
        indexWriter ->
            when(indexWriter.getInternalWriterData())
                .thenReturn(new LuceneCommitData.IndexWriterData(true)));
    var indexResources =
        LuceneIndexResourcesInitializer.initialize(
            numPartitions,
            mocks.directoryFactory,
            mocks.singleLuceneIndexWriterCreator,
            mocks.luceneSearcherManagerCreator);
    Assert.assertEquals(mocks.directories, indexResources.directories);
    Assert.assertEquals(mocks.metadataDirectories, indexResources.metadataDirectories);
    assertThat(indexResources.luceneIndexWriter).isInstanceOf(MultiLuceneIndexWriter.class);
    Assert.assertEquals(
        mocks.singleLuceneIndexWriters,
        ((MultiLuceneIndexWriter) indexResources.luceneIndexWriter).getSingleLuceneIndexWriters());
    Assert.assertEquals(mocks.luceneSearcherManagers, indexResources.luceneSearcherManagers);
    CheckedStream.from(mocks.singleLuceneIndexWriters)
        .forEachChecked(indexWriter -> verify(indexWriter, never()).deleteAll(any()));
  }

  @Test
  public void testHandleUncleanShutdown() throws IOException {
    int numPartitions = 4;
    Mocks mocks = new Mocks(numPartitions);
    when(mocks.singleLuceneIndexWriters.get(0).getInternalWriterData())
        .thenReturn(new LuceneCommitData.IndexWriterData(true));
    when(mocks.singleLuceneIndexWriters.get(1).getInternalWriterData())
        .thenReturn(new LuceneCommitData.IndexWriterData(true));
    var indexResources =
        LuceneIndexResourcesInitializer.initialize(
            numPartitions,
            mocks.directoryFactory,
            mocks.singleLuceneIndexWriterCreator,
            mocks.luceneSearcherManagerCreator);
    Assert.assertEquals(mocks.directories, indexResources.directories);
    Assert.assertEquals(mocks.metadataDirectories, indexResources.metadataDirectories);
    assertThat(indexResources.luceneIndexWriter).isInstanceOf(MultiLuceneIndexWriter.class);
    Assert.assertEquals(
        mocks.singleLuceneIndexWriters,
        ((MultiLuceneIndexWriter) indexResources.luceneIndexWriter).getSingleLuceneIndexWriters());
    Assert.assertEquals(mocks.luceneSearcherManagers, indexResources.luceneSearcherManagers);
    verify(mocks.singleLuceneIndexWriters.get(0), never()).deleteAll(any());
    verify(mocks.singleLuceneIndexWriters.get(1), never()).deleteAll(any());
    verify(mocks.singleLuceneIndexWriters.get(2), times(1)).deleteAll(any());
    verify(mocks.singleLuceneIndexWriters.get(3), times(1)).deleteAll(any());
  }
}
