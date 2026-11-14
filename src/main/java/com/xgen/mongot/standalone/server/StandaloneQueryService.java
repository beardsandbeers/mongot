package com.xgen.mongot.standalone.server;

import com.google.errorprone.annotations.Var;
import com.google.protobuf.ByteString;
import com.xgen.mongot.cursor.CursorQuery;
import com.xgen.mongot.cursor.batch.BatchCursorOptions;
import com.xgen.mongot.cursor.batch.QueryCursorOptions;
import com.xgen.mongot.index.query.MaterializedVectorSearchQuery;
import com.xgen.mongot.index.query.QueryExecutionContext;
import com.xgen.mongot.index.query.QueryOptimizationFlags;
import com.xgen.mongot.index.query.SearchQuery;
import com.xgen.mongot.index.query.VectorSearchQuery;
import com.xgen.mongot.standalone.StandaloneBson;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.grpc.GetMoreRequest;
import com.xgen.mongot.standalone.grpc.KillCursorRequest;
import com.xgen.mongot.standalone.grpc.KillCursorResponse;
import com.xgen.mongot.standalone.grpc.QueryRequest;
import com.xgen.mongot.standalone.grpc.QueryResponse;
import com.xgen.mongot.standalone.grpc.QueryServiceGrpc;
import com.xgen.mongot.standalone.runtime.StandaloneRuntime;
import com.xgen.mongot.util.Bytes;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.bson.types.ObjectId;

/** Public queries using ordinary mongot parsers, readers and snapshot-pinning cursors. */
final class StandaloneQueryService extends QueryServiceGrpc.QueryServiceImplBase
    implements AutoCloseable {
  private static final long IDLE_NANOS = Duration.ofMinutes(2).toNanos();
  private final StandaloneRuntime runtime;
  private final RpcDispatcher dispatcher;
  private final StandaloneJoinPlanner joinPlanner;
  private final Semaphore capacity = new Semaphore(1024);
  private final ConcurrentHashMap<Long, Lease> leases = new ConcurrentHashMap<>();

  private static final class Lease {
    private final String principal;
    private final String index;
    private final String generation;
    private final Optional<StandaloneJoinPlanner.Plan> joinPlan;
    private volatile long lastAccess = System.nanoTime();

    private Lease(
        String principal,
        String index,
        String generation,
        Optional<StandaloneJoinPlanner.Plan> joinPlan) {
      this.principal = principal;
      this.index = index;
      this.generation = generation;
      this.joinPlan = joinPlan;
    }
  }

  StandaloneQueryService(StandaloneRuntime runtime, RpcDispatcher dispatcher) {
    this.runtime = runtime;
    this.dispatcher = dispatcher;
    this.joinPlanner = new StandaloneJoinPlanner(runtime);
  }

  @Override
  public void search(QueryRequest request, StreamObserver<QueryResponse> observer) {
    this.dispatcher.unary(
        request.getSerializedSize(), observer, () -> query(request, false), this::abandoned);
  }

  @Override
  public void vectorSearch(QueryRequest request, StreamObserver<QueryResponse> observer) {
    this.dispatcher.unary(
        request.getSerializedSize(), observer, () -> query(request, true), this::abandoned);
  }

  private void abandoned(QueryResponse response) {
    var lease = this.leases.get(response.getCursorId());
    if (lease != null) {
      synchronized (lease) {
        release(response.getCursorId(), lease);
      }
    }
  }

  QueryResponse query(QueryRequest request, boolean vector) {
    var desired = this.runtime.catalog().getIndex(request.getIndexId());
    StandaloneAuthInterceptor.requireIndex(desired.getIndexId());
    if (desired.getLifecycleState().equals("DROPPED")) {
      throw Status.NOT_FOUND.withDescription("Index was dropped").asRuntimeException();
    }
    var generation =
        this.runtime
            .liveIndexes()
            .getIndexById(new ObjectId(request.getIndexId()))
            .filter(index -> this.runtime.ingestion().isGenerationReady(index.getGenerationId()))
            .orElseThrow(
                () ->
                    Status.UNAVAILABLE
                        .withDescription("Index is not query-ready")
                        .asRuntimeException());
    String generationId = generation.getGenerationId().uniqueString();
    if (!request.getGenerationId().isEmpty() && !generationId.equals(request.getGenerationId())) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Requested generation is no longer live")
          .asRuntimeException();
    }
    int size = batchSize(request.getBatchSize());
    if (!this.capacity.tryAcquire()) {
      throw Status.RESOURCE_EXHAUSTED
          .withDescription("Open cursor capacity is full")
          .asRuntimeException();
    }
    @Var long cursorId = 0;
    @Var boolean retained = false;
    @Var StandaloneJoinPlanner.Plan joinPlan = null;
    try {
      var expression = new BsonDocument();
      expression.putAll(
          StandaloneBson.document(request.getQueryBson().toByteArray(), 1024 * 1024, 100));
      if (expression.containsKey("index")
          && !expression.getString("index").getValue().equals(desired.getName())) {
        throw Status.INVALID_ARGUMENT
            .withDescription("Query index does not match index_id")
            .asRuntimeException();
      }
      expression.put("index", new org.bson.BsonString(desired.getName()));
      CursorQuery query;
      if (vector) {
        var parsed = VectorSearchQuery.fromBson(expression);
        var input =
            parsed
                .criteria()
                .queryVector()
                .orElseThrow(
                    () ->
                        Status.INVALID_ARGUMENT
                            .withDescription("Standalone vector queries require queryVector")
                            .asRuntimeException());
        var deadline =
            Optional.ofNullable(Context.current().getDeadline())
                .map(
                    value ->
                        System.currentTimeMillis()
                            + Math.max(0, value.timeRemaining(TimeUnit.MILLISECONDS)));
        query =
            new CursorQuery.Vector(
                new MaterializedVectorSearchQuery(parsed, input),
                QueryExecutionContext.withDeadline(deadline));
      } else {
        var parsed = SearchQuery.fromBson(expression);
        if (StandaloneJoinPlanner.containsJoin(StandaloneJoinPlanner.operator(parsed))) {
          joinPlan = this.joinPlanner.prepare(parsed, generation.getGenerationId());
          query = new CursorQuery.Search(joinPlan.query(), Optional.of(joinPlan.snapshot()));
        } else {
          query = new CursorQuery.Search(parsed);
        }
      }
      var cursor =
          this.runtime
              .cursors()
              .newCursor(
                  generation.getGenerationId(),
                  query,
                  new QueryCursorOptions(Optional.empty(), Optional.of(size), false),
                  QueryOptimizationFlags.DEFAULT_OPTIONS);
      cursorId = cursor.cursorId;
      if (joinPlan != null) {
        joinPlan.verifyDependencies();
      }
      if (Context.current().isCancelled()) {
        throw Status.CANCELLED.asRuntimeException();
      }
      var batch =
          this.runtime
              .cursors()
              .getNextBatch(
                  cursorId,
                  Bytes.ofBytes(4 * 1024 * 1024),
                  new BatchCursorOptions(Optional.empty(), Optional.of(size)));
      var response =
          QueryResponse.newBuilder()
              .setGenerationId(generationId)
              .setBatchBson(encode(new BsonDocument("results", batch.batch)))
              .setMetadataBson(encode(cursor.metaResults.applyAggregationLimit().toBson()));
      if (joinPlan != null) {
        joinPlan.verifyDependencies();
      }
      if (!batch.exhausted) {
        this.leases.put(
            cursorId,
            new Lease(
                StandaloneAuthInterceptor.principal().name(),
                desired.getIndexId(),
                generationId,
                Optional.ofNullable(joinPlan)));
        response.setCursorId(cursorId);
        retained = true;
      }
      return response.build();
    } catch (Exception e) {
      throw queryError(e);
    } finally {
      if (!retained) {
        try {
          if (cursorId != 0) {
            this.runtime.cursors().killCursor(cursorId);
          }
        } finally {
          try {
            if (joinPlan != null) {
              closeJoinPlan(joinPlan);
            }
          } finally {
            this.capacity.release();
          }
        }
      }
    }
  }

  @Override
  public void getMore(GetMoreRequest request, StreamObserver<QueryResponse> observer) {
    this.dispatcher.unary(request.getSerializedSize(), observer, () -> nextBatch(request));
  }

  QueryResponse nextBatch(GetMoreRequest request) {
    var lease = requireLease(request.getCursorId());
    synchronized (lease) {
      try {
        lease.joinPlan.ifPresent(plan -> plan.activate(Context.current()));
        var batch =
            this.runtime
                .cursors()
                .getNextBatch(
                    request.getCursorId(),
                    Bytes.ofBytes(4 * 1024 * 1024),
                    new BatchCursorOptions(
                        Optional.empty(), Optional.of(batchSize(request.getBatchSize()))));
        lease.joinPlan.ifPresent(StandaloneJoinPlanner.Plan::verifyDependencies);
        lease.lastAccess = System.nanoTime();
        if (batch.exhausted) {
          release(request.getCursorId(), lease);
        }
        return QueryResponse.newBuilder()
            .setCursorId(batch.exhausted ? 0 : request.getCursorId())
            .setGenerationId(lease.generation)
            .setBatchBson(encode(new BsonDocument("results", batch.batch)))
            .build();
      } catch (Exception e) {
        release(request.getCursorId(), lease);
        throw queryError(e);
      }
    }
  }

  void discardCursor(long id) {
    var lease = this.leases.get(id);
    if (lease != null) {
      synchronized (lease) {
        release(id, lease);
      }
    }
  }

  @Override
  public void killCursor(KillCursorRequest request, StreamObserver<KillCursorResponse> observer) {
    this.dispatcher.unary(
        request.getSerializedSize(),
        observer,
        () -> {
          var lease = requireLease(request.getCursorId());
          synchronized (lease) {
            release(request.getCursorId(), lease);
          }
          return KillCursorResponse.getDefaultInstance();
        });
  }

  private Lease requireLease(long id) {
    var lease = this.leases.get(id);
    if (lease == null
        || !lease.principal.equals(StandaloneAuthInterceptor.principal().name())
        || !StandaloneAuthInterceptor.principal().allows(lease.index)) {
      throw Status.NOT_FOUND
          .withDescription("Cursor does not exist for this principal")
          .asRuntimeException();
    }
    synchronized (lease) {
      // A search already in progress can register its cursor after drop's invalidation pass.
      // Consult the index catalog so that such a cursor cannot serve another batch.
      try {
        this.runtime.catalog().getIndex(lease.index);
        lease.joinPlan.ifPresent(StandaloneJoinPlanner.Plan::verifyDependencies);
      } catch (io.grpc.StatusRuntimeException e) {
        release(id, lease);
        throw e;
      }
    }
    return lease;
  }

  void indexDropped(String indexId) {
    this.leases.forEach(
        (id, lease) -> {
          if (lease.index.equals(indexId)
              || lease.joinPlan.map(plan -> plan.dependsOn(indexId)).orElse(false)) {
            synchronized (lease) {
              release(id, lease);
            }
          }
        });
  }

  void expireIdle() {
    long cutoff = System.nanoTime() - IDLE_NANOS;
    this.leases.forEach(
        (id, lease) -> {
          if (lease.lastAccess < cutoff) {
            synchronized (lease) {
              if (lease.lastAccess < cutoff) {
                release(id, lease);
              }
            }
          }
        });
  }

  private void release(long id, Lease lease) {
    if (this.leases.remove(id, lease)) {
      try {
        this.runtime.cursors().killCursor(id);
      } finally {
        try {
          lease.joinPlan.ifPresent(StandaloneQueryService::closeJoinPlan);
        } finally {
          this.capacity.release();
        }
      }
    }
  }

  private static void closeJoinPlan(StandaloneJoinPlanner.Plan plan) {
    try {
      plan.close();
    } catch (IOException e) {
      throw Status.UNAVAILABLE
          .withDescription("Could not release join readers")
          .withCause(e)
          .asRuntimeException();
    }
  }

  private ByteString encode(BsonDocument document) {
    byte[] bytes = StandaloneDefinitionValidator.encode(document);
    if (bytes.length > this.runtime.config().documentLimits().maxBatchBytes()) {
      throw Status.RESOURCE_EXHAUSTED
          .withDescription("Query response exceeds the configured byte limit")
          .asRuntimeException();
    }
    return ByteString.copyFrom(bytes);
  }

  private static int batchSize(int requested) {
    if (requested < 0 || requested > 1000) {
      throw Status.INVALID_ARGUMENT
          .withDescription("batch_size must be between 1 and 1000")
          .asRuntimeException();
    }
    return requested == 0 ? 100 : requested;
  }

  private static RuntimeException queryError(Exception e) {
    if (e instanceof io.grpc.StatusRuntimeException status) {
      return status;
    }
    if (e instanceof com.xgen.mongot.cursor.MongotCursorNotFoundException) {
      return Status.NOT_FOUND
          .withDescription("Cursor expired or its index was dropped")
          .asRuntimeException();
    }
    if (e instanceof com.xgen.mongot.util.bson.parser.BsonParseException
        || e instanceof com.xgen.mongot.index.query.InvalidQueryException
        || e instanceof IllegalArgumentException) {
      return Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException();
    }
    if (e instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
    return Status.UNAVAILABLE
        .withDescription("Local query could not complete")
        .withCause(e)
        .asRuntimeException();
  }

  @Override
  public void close() {
    this.leases.forEach(this::release);
  }
}
