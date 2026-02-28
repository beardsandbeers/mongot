package com.xgen.mongot.index.lucene.init;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.xgen.mongot.index.EncodedUserData;
import com.xgen.mongot.index.lucene.directory.IndexDirectoryFactory;
import com.xgen.mongot.index.lucene.searcher.LuceneSearcherManager;
import com.xgen.mongot.index.lucene.writer.LuceneIndexWriter;
import com.xgen.mongot.index.lucene.writer.MultiLuceneIndexWriter;
import com.xgen.mongot.index.lucene.writer.SingleLuceneIndexWriter;
import com.xgen.mongot.util.Check;
import com.xgen.mongot.util.Crash;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.lucene.store.Directory;

/**
 * This class will be used to initialize {@link com.xgen.mongot.index.lucene.LuceneSearchIndex} and
 * {@link com.xgen.mongot.index.lucene.LuceneVectorIndex}.
 *
 * <p>When there is an exception during the initialization, it will attempt to close resources.
 */
public class LuceneIndexResourcesInitializer {
  public static class Resources {
    // One item in this list is for one index partition.
    public final ImmutableList<Directory> directories;
    public final ImmutableList<File> metadataDirectories;

    // This is for all the index partitions.
    public final LuceneIndexWriter luceneIndexWriter;

    // One item in this list is for one index partition.
    public final ImmutableList<LuceneSearcherManager> luceneSearcherManagers;

    public Resources(
        ImmutableList<Directory> directories,
        ImmutableList<File> metadataDirectories,
        LuceneIndexWriter luceneIndexWriter,
        ImmutableList<LuceneSearcherManager> luceneSearcherManagers) {
      this.directories = directories;
      this.metadataDirectories = metadataDirectories;
      this.luceneIndexWriter = luceneIndexWriter;
      this.luceneSearcherManagers = luceneSearcherManagers;
    }
  }

  @FunctionalInterface
  public interface SingleLuceneIndexWriterCreator {
    SingleLuceneIndexWriter create(Directory directory, int indexPartitionId) throws IOException;
  }

  @FunctionalInterface
  public interface LuceneSearcherManagerCreator {
    LuceneSearcherManager create(SingleLuceneIndexWriter luceneIndexWriter) throws IOException;
  }

  public static Resources initialize(
      int numPartitions,
      IndexDirectoryFactory directoryFactory,
      SingleLuceneIndexWriterCreator singleLuceneIndexWriterCreator,
      LuceneSearcherManagerCreator luceneSearcherManagerCreator)
      throws IOException {
    return initialize(
        numPartitions,
        directoryFactory,
        singleLuceneIndexWriterCreator,
        luceneSearcherManagerCreator,
        closeable -> {
          // If we failed to close a resource, it's hard to ensure staring from a fresh state.
          Crash.because("Failed to release resources when initializing a LuceneIndex.")
              .ifThrows(closeable::close);
        });
  }

  // This method is used for testing. The `resourcesCloser` is used as a workaround of verifying
  // final methods by Mockito.
  @VisibleForTesting
  static Resources initialize(
      int numPartitions,
      IndexDirectoryFactory directoryFactory,
      SingleLuceneIndexWriterCreator singleLuceneIndexWriterCreator,
      LuceneSearcherManagerCreator luceneSearcherManagerCreator,
      Consumer<Closeable> resourcesCloser)
      throws IOException {
    Check.checkState(numPartitions > 0, "numPartitions should be larger than 0");
    List<Directory> directories = new ArrayList<>();
    List<File> metadataDirectories = new ArrayList<>();
    List<SingleLuceneIndexWriter> singleLuceneIndexWriters = new ArrayList<>();
    List<LuceneSearcherManager> luceneSearcherManagers = new ArrayList<>();

    try {
      for (int indexPartitionId = 0; indexPartitionId < numPartitions; ++indexPartitionId) {
        directories.add(directoryFactory.create(indexPartitionId));
        metadataDirectories.add(directoryFactory.createMetadata(indexPartitionId));
        // TODO(CLOUDP-250488): Get deletion policy from snapshotter here and pass to the writer.
        SingleLuceneIndexWriter writer =
            singleLuceneIndexWriterCreator.create(
                directories.get(indexPartitionId), indexPartitionId);
        singleLuceneIndexWriters.add(writer);
      }
      // Handle unclean mongot shutdown.
      if (numPartitions > 1
          && singleLuceneIndexWriters.getFirst().getInternalWriterData().isCleared()) {
        // If the first writer is cleared but some other writers are not, we will attempt to clear
        // these writers as well. So that we can release disk resources.
        EncodedUserData encodedUserData = singleLuceneIndexWriters.getFirst().getCommitUserData();
        for (int indexPartitionId = 1; indexPartitionId < numPartitions; ++indexPartitionId) {
          if (!singleLuceneIndexWriters.get(indexPartitionId).getInternalWriterData().isCleared()) {
            singleLuceneIndexWriters.get(indexPartitionId).deleteAll(encodedUserData);
          }
        }
      }
      // Create search managers after updates to index writers. So that managers can be created on
      // the latest data.
      for (int indexPartitionId = 0; indexPartitionId < numPartitions; ++indexPartitionId) {
        luceneSearcherManagers.add(
            luceneSearcherManagerCreator.create(singleLuceneIndexWriters.get(indexPartitionId)));
      }
    } catch (Exception e) {
      // Resources must be closed in the following order.
      luceneSearcherManagers.forEach(resourcesCloser);
      singleLuceneIndexWriters.forEach(resourcesCloser);
      directories.forEach(resourcesCloser);
      throw e;
    }
    LuceneIndexWriter luceneIndexWriter =
        numPartitions > 1
            ? MultiLuceneIndexWriter.create(singleLuceneIndexWriters)
            : singleLuceneIndexWriters.getFirst();
    return new Resources(
        ImmutableList.copyOf(directories),
        ImmutableList.copyOf(metadataDirectories),
        luceneIndexWriter,
        ImmutableList.copyOf(luceneSearcherManagers));
  }
}
