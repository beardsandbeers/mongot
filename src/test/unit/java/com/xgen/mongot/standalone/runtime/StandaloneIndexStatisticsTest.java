package com.xgen.mongot.standalone.runtime;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.index.statistics.LuceneStatisticsCollector;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DocumentWriteConcern;
import com.xgen.mongot.standalone.grpc.GetIndexFieldStatsRequest;
import com.xgen.mongot.standalone.grpc.GetIndexStatsRequest;
import com.xgen.mongot.standalone.grpc.IndexFieldStatsResponse;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.StatsCollectionState;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.metrics.StandaloneMetrics;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.BsonDocument;
import org.bson.types.ObjectId;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StandaloneIndexStatisticsTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void summary_cacheAndRefresh_observesWritesAfterCacheExpiry() throws Exception {
    try (var fixture = new Fixture()) {
      String id = fixture.create("summary");
      fixture.write(id, "{_id:'a',title:'first',amount:7}");
      var first =
          fixture
              .statistics
              .summary(GetIndexStatsRequest.newBuilder().setIndexId(id).build())
              .get(5, TimeUnit.SECONDS);
      fixture.write(id, "{_id:'b',title:'second',amount:8}");

      var cached =
          fixture
              .statistics
              .summary(GetIndexStatsRequest.newBuilder().setIndexId(id).build())
              .get(5, TimeUnit.SECONDS);
      fixture.time.addAndGet(Duration.ofSeconds(6).toNanos());
      var fresh =
          fixture
              .statistics
              .summary(GetIndexStatsRequest.newBuilder().setIndexId(id).build())
              .get(5, TimeUnit.SECONDS);

      assertThat(cached.getSnapshotId()).isEqualTo(first.getSnapshotId());
      assertThat(cached.getDocumentCount()).isEqualTo(1);
      assertThat(fresh.getSnapshotId()).isNotEqualTo(first.getSnapshotId());
      assertThat(fresh.getDocumentCount()).isEqualTo(2);
    }
  }

  @Test
  public void fields_tokens_bindPrincipalAndExpireWithoutRetainingReaders() throws Exception {
    try (var fixture = new Fixture()) {
      String id = fixture.create("tokens");
      fixture.write(id, "{_id:'a',title:'one',amount:7}");
      var first = fixture.fields(id, 1);
      var next =
          GetIndexFieldStatsRequest.newBuilder()
              .setIndexId(id)
              .setPageSize(1)
              .setPageToken(first.getNextPageToken())
              .build();

      assertThat(
              assertThrows(
                      StatusRuntimeException.class, () -> fixture.statistics.fields(next, "bob"))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
      var generation = fixture.runtime.liveIndexes().getIndexById(new ObjectId(id)).orElseThrow();
      assertThat(fixture.runtime.ingestion().dropIndex(generation.getGenerationId()).isDone())
          .isTrue();
      // Completed pages retain only values: retirement did not wait for these idle tokens.
    }
    try (var fixture = new Fixture()) {
      String id = fixture.create("expiry");
      fixture.write(id, "{_id:'a',title:'one',amount:7}");
      var first = fixture.fields(id, 1);
      fixture.time.addAndGet(Duration.ofMinutes(3).toNanos());

      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          fixture.statistics.fields(
                              GetIndexFieldStatsRequest.newBuilder()
                                  .setIndexId(id)
                                  .setPageSize(1)
                                  .setPageToken(first.getNextPageToken())
                                  .build(),
                              "alice"))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }
  }

  @Test
  public void fields_generationReplacement_rejectsOldSnapshotTokens() throws Exception {
    try (var fixture = new Fixture()) {
      String id = fixture.create("replace");
      fixture.write(id, "{_id:'a',title:'one',amount:7}");
      var first = fixture.fields(id, 1);
      fixture
          .runtime
          .catalog()
          .updateIndex(
              UpdateIndexRequest.newBuilder()
                  .setIndexId(id)
                  .setExpectedRevision(1)
                  .setDefinitionBson(bson("{mappings:{dynamic:true}}"))
                  .build());
      fixture.runtime.catalogChanged();
      fixture.ready(id, 2);

      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          fixture.statistics.fields(
                              GetIndexFieldStatsRequest.newBuilder()
                                  .setIndexId(id)
                                  .setPageSize(1)
                                  .setPageToken(first.getNextPageToken())
                                  .build(),
                              "alice"))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }
  }

  @Test
  public void statisticsLease_retirement_defersCleanupUntilReleaseOutsideIngestionMonitor()
      throws Exception {
    try (var fixture = new Fixture()) {
      String id = fixture.create("lease");
      var generation = fixture.runtime.liveIndexes().getIndexById(new ObjectId(id)).orElseThrow();
      var lease = fixture.runtime.ingestion().acquireStatisticsLease(generation.getGenerationId());
      var heldMonitor = new AtomicBoolean(true);

      var retirement = fixture.runtime.ingestion().dropIndex(generation.getGenerationId());
      var continuation =
          retirement.thenRun(() -> heldMonitor.set(Thread.holdsLock(fixture.runtime.ingestion())));

      assertThat(retirement.isDone()).isFalse();
      assertThat(lease.retired()).isTrue();
      assertThat(lease.index().isClosed()).isFalse();
      lease.close();
      continuation.get(5, TimeUnit.SECONDS);
      lease.close();
      assertThat(heldMonitor.get()).isFalse();
      assertThat(retirement.isDone()).isTrue();
    }
  }

  @Test
  public void summary_queueAndCancellation_boundsWorkAndReleasesActiveGenerationLeases()
      throws Exception {
    var blocker = new BlockingCollector();
    try (var fixture = new Fixture(blocker::collect)) {
      var ids = fixture.createMany(20);
      var first =
          fixture.statistics.summary(
              GetIndexStatsRequest.newBuilder().setIndexId(ids.get(0)).build());
      var second =
          fixture.statistics.summary(
              GetIndexStatsRequest.newBuilder().setIndexId(ids.get(1)).build());
      assertThat(blocker.started.await(10, TimeUnit.SECONDS)).isTrue();
      var duplicate =
          fixture.statistics.summary(
              GetIndexStatsRequest.newBuilder().setIndexId(ids.get(0)).build());
      var queued = fixture.enqueue(ids.subList(2, 18));

      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          fixture.statistics.summary(
                              GetIndexStatsRequest.newBuilder().setIndexId(ids.get(18)).build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(blocker.calls.get()).isEqualTo(2);
      first.cancel(false);
      assertThat(duplicate.isDone()).isFalse();
      var generation =
          fixture.runtime.liveIndexes().getIndexById(new ObjectId(ids.get(0))).orElseThrow();
      fixture.runtime.ingestion().dropIndex(generation.getGenerationId()).get(5, TimeUnit.SECONDS);
      assertThrows(java.util.concurrent.CompletionException.class, duplicate::join);
      cancel(queued);
      blocker.release.countDown();
      assertThat(second.get(5, TimeUnit.SECONDS).getDocumentCount()).isEqualTo(0);
    }
  }

  private static void cancel(List<? extends CompletableFuture<?>> futures) {
    futures.forEach(future -> future.cancel(false));
  }

  private static ByteString bson(String json) {
    return ByteString.copyFrom(StandaloneDefinitionValidator.encode(BsonDocument.parse(json)));
  }

  private static final class BlockingCollector {
    private final CountDownLatch started = new CountDownLatch(2);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();

    private com.xgen.mongot.index.statistics.IndexStatistics collect(
        com.xgen.mongot.index.statistics.LuceneStatisticsSnapshot snapshot,
        com.xgen.mongot.index.definition.IndexDefinition definition,
        boolean detailed,
        java.util.OptionalLong writerHeap,
        java.util.function.BooleanSupplier cancelled)
        throws java.io.IOException {
      this.calls.incrementAndGet();
      this.started.countDown();
      try {
        while (!this.release.await(10, TimeUnit.MILLISECONDS)) {
          if (cancelled.getAsBoolean()) {
            throw new CancellationException();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException();
      }
      return LuceneStatisticsCollector.collect(
          snapshot, definition, detailed, writerHeap, cancelled);
    }
  }

  private final class Fixture implements AutoCloseable {
    private final StandaloneRuntime runtime;
    private final StandaloneIndexStatistics statistics;
    private final AtomicLong time = new AtomicLong(1);

    private Fixture() throws Exception {
      this(LuceneStatisticsCollector::collect);
    }

    private Fixture(StandaloneIndexStatistics.Collector collector) throws Exception {
      this.runtime =
          new StandaloneRuntime(
              StandaloneConfig.development(
                  StandaloneIndexStatisticsTest.this.folder.newFolder().toPath(), 0));
      this.statistics =
          new StandaloneIndexStatistics(
              this.runtime.catalog(),
              this.runtime.liveIndexes(),
              this.runtime.ingestion(),
              Duration.ofMinutes(1),
              StandaloneMetrics.disabled(),
              this.time::get,
              collector);
    }

    private String create(String name) throws Exception {
      String id =
          this.runtime
              .catalog()
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName(name)
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(bson("{mappings:{dynamic:true}}"))
                      .build())
              .getIndexId();
      this.runtime.catalogChanged();
      ready(id, 1);
      return id;
    }

    private List<String> createMany(int count) throws Exception {
      var result = new ArrayList<String>();
      for (int i = 0; i < count; i++) {
        result.add(create("index-" + i));
      }
      return result;
    }

    private List<CompletableFuture<com.xgen.mongot.standalone.grpc.IndexStats>> enqueue(
        List<String> ids) {
      return ids.stream()
          .map(
              id ->
                  this.statistics.summary(GetIndexStatsRequest.newBuilder().setIndexId(id).build()))
          .toList();
    }

    private void ready(String id, long revision) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        var index = this.runtime.describeIndex(id);
        if (index.getLifecycleState().equals("READY") && index.getActiveRevision() == revision) {
          return;
        }
        Thread.sleep(10);
      }
      throw new AssertionError("Index is not ready");
    }

    private void write(String id, String json) {
      this.runtime
          .ingestion()
          .coordinate(
              () ->
                  this.runtime
                      .ingestion()
                      .write(
                          id,
                          1,
                          "",
                          List.of(bson(json)),
                          false,
                          DocumentWriteConcern.DOCUMENT_WRITE_CONCERN_VISIBLE));
    }

    private IndexFieldStatsResponse fields(String id, int pageSize) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        var value =
            this.statistics.fields(
                GetIndexFieldStatsRequest.newBuilder().setIndexId(id).setPageSize(pageSize).build(),
                "alice");
        if (value.getState() == StatsCollectionState.STATS_COLLECTION_STATE_READY) {
          return value;
        }
        Thread.sleep(10);
      }
      throw new AssertionError("Field statistics did not finish");
    }

    @Override
    public void close() throws Exception {
      try {
        this.statistics.close();
      } finally {
        this.runtime.close();
      }
    }
  }
}
