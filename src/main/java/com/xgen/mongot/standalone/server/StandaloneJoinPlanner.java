package com.xgen.mongot.standalone.server;

import com.xgen.mongot.index.SearchIndexReader.QuerySnapshot;
import com.xgen.mongot.index.analyzer.definition.StockNormalizerName;
import com.xgen.mongot.index.query.CollectorQuery;
import com.xgen.mongot.index.query.OperatorQuery;
import com.xgen.mongot.index.query.SearchQuery;
import com.xgen.mongot.index.query.collectors.AggregationCollector;
import com.xgen.mongot.index.query.collectors.FacetCollector;
import com.xgen.mongot.index.query.operators.CompoundClause;
import com.xgen.mongot.index.query.operators.CompoundOperator;
import com.xgen.mongot.index.query.operators.EmbeddedDocumentOperator;
import com.xgen.mongot.index.query.operators.HasAncestorOperator;
import com.xgen.mongot.index.query.operators.HasRootOperator;
import com.xgen.mongot.index.query.operators.JoinOperator;
import com.xgen.mongot.index.query.operators.KnnBetaOperator;
import com.xgen.mongot.index.query.operators.MaterializedJoinOperator;
import com.xgen.mongot.index.query.operators.Operator;
import com.xgen.mongot.index.query.operators.VectorSearchOperator;
import com.xgen.mongot.index.version.GenerationId;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.runtime.StandaloneRuntime;
import io.grpc.Context;
import io.grpc.Status;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.util.IOUtils;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.Nullable;

/** Resolves authorized index dependencies and bounded token membership before opening a cursor. */
final class StandaloneJoinPlanner {
  static final int MAX_JOINS = 8;
  static final int MAX_KEYS = 100_000;
  static final long MAX_QUERY_BYTES = 32L * 1024 * 1024;
  static final long MAX_CONCURRENT_BYTES = 256L * 1024 * 1024;
  static final long MAX_FOREIGN_WORK = 10_000_000;

  private final StandaloneRuntime runtime;
  private final AtomicLong retainedBytes = new AtomicLong();

  StandaloneJoinPlanner(StandaloneRuntime runtime) {
    this.runtime = runtime;
    runtime.metrics().gauge("joins.reservedBytes", this.retainedBytes, AtomicLong::doubleValue);
  }

  static Operator operator(SearchQuery query) {
    return switch (query) {
      case OperatorQuery search -> search.operator();
      case CollectorQuery search -> search.collector().getOperator().orElseThrow();
    };
  }

  static boolean containsJoin(Operator operator) {
    return switch (operator) {
      case JoinOperator ignored -> true;
      case MaterializedJoinOperator ignored -> true;
      case CompoundOperator compound ->
          compound.getOperators().anyMatch(StandaloneJoinPlanner::containsJoin);
      case EmbeddedDocumentOperator embedded -> containsJoin(embedded.operator());
      case HasRootOperator root -> containsJoin(root.operator());
      case HasAncestorOperator ancestor -> containsJoin(ancestor.operator());
      case KnnBetaOperator knn ->
          knn.filter().map(StandaloneJoinPlanner::containsJoin).orElse(false);
      default -> false;
    };
  }

  Plan prepare(SearchQuery query, GenerationId localGeneration) throws Exception {
    if (query.returnScope().isPresent()) {
      throw invalid("Joins do not support returnScope");
    }
    List<JoinOperator> joins = new ArrayList<>();
    validate(operator(query), false, false, joins);
    if (joins.size() > MAX_JOINS) {
      throw exhausted("Too many join predicates (maximum " + MAX_JOINS + ")");
    }
    var plan = new Plan(query);
    long startNanos = System.nanoTime();
    try {
      plan.reserveBytes(8192L + 8L * query.toBson().toJson().length());
      plan.add(localGeneration.indexId.toHexString(), Optional.of(localGeneration));
      // Authorize and pin every dependency before executing any foreign predicate.
      Map<JoinOperator, String> targets = new HashMap<>();
      for (var join : joins) {
        String id =
            join.from().isPresent()
                ? this.runtime.catalog().getIndexByName(join.from().get()).getIndexId()
                : localGeneration.indexId.toHexString();
        plan.add(id, Optional.empty());
        targets.put(join, id);
      }
      var localSnapshot =
          Objects.requireNonNull(plan.snapshots.get(localGeneration.indexId.toHexString()));
      plan.localSnapshot = localSnapshot;
      Map<JoinOperator, MaterializedJoinOperator> resolved = new HashMap<>();
      for (var join : joins) {
        var foreign = Objects.requireNonNull(plan.snapshots.get(targets.get(join)));
        var localToken = localSnapshot.tokenField(join.localField());
        var foreignToken = foreign.tokenField(join.foreignField());
        if (!localToken
            .normalizer()
            .orElse(StockNormalizerName.NONE)
            .equals(foreignToken.normalizer().orElse(StockNormalizerName.NONE))) {
          throw invalid("Join fields must use the same token normalizer");
        }
        var keys = new HashSet<String>();
        long foreignStart = System.nanoTime();
        try {
          foreign.collectKeys(
              join.operator(),
              join.foreignField(),
              key -> {
                plan.checkWork();
                if (keys.add(key)) {
                  plan.reserveKey(key);
                }
              },
              plan::checkWork);
        } finally {
          this.runtime.metrics().elapsed("joins.foreign", foreignStart);
        }
        resolved.put(join, new MaterializedJoinOperator(join, List.copyOf(keys)));
      }
      var materialized = materialize(operator(query), resolved);
      plan.query =
          switch (query) {
            case OperatorQuery local -> local.withOperator(materialized);
            case CollectorQuery collector -> collector.withOperator(materialized);
          };
      // Foreign searchers are unnecessary once membership owns immutable token strings.
      for (var entry : plan.snapshots.entrySet()) {
        if (entry.getValue() != localSnapshot) {
          entry.getValue().close();
        }
      }
      plan.snapshots.clear();
      plan.snapshots.put(localGeneration.indexId.toHexString(), localSnapshot);
      plan.activate(Context.current());
      plan.verifyDependencies();
      this.runtime.metrics().count("joins.keys", plan.keys);
      return plan;
    } catch (Exception | Error e) {
      try {
        plan.close();
      } catch (IOException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    } finally {
      this.runtime.metrics().elapsed("joins.prepare", startNanos);
    }
  }

  private static void validate(
      Operator operator, boolean filter, boolean foreign, List<JoinOperator> joins) {
    if (operator instanceof CompoundOperator compound) {
      compound.filter().operators().forEach(child -> validate(child, true, foreign, joins));
      compound.mustNot().operators().forEach(child -> validate(child, true, foreign, joins));
      compound.must().operators().forEach(child -> validate(child, filter, foreign, joins));
      compound.should().operators().forEach(child -> validate(child, filter, foreign, joins));
    } else if (operator instanceof JoinOperator join) {
      if (foreign || !filter) {
        throw invalid("join is allowed only in filter/exclusion positions and cannot be nested");
      }
      joins.add(join);
      validate(join.operator(), true, true, joins);
    } else if (operator instanceof EmbeddedDocumentOperator
        || operator instanceof HasAncestorOperator
        || operator instanceof HasRootOperator
        || operator instanceof KnnBetaOperator
        || operator instanceof VectorSearchOperator) {
      throw invalid("Joins do not support embedded-document or vector operators");
    }
  }

  private static Operator materialize(
      Operator operator, Map<JoinOperator, MaterializedJoinOperator> joins) {
    if (operator instanceof JoinOperator join) {
      return joins.get(join);
    }
    if (operator instanceof CompoundOperator compound) {
      return new CompoundOperator(
          compound.score(),
          materialize(compound.filter(), joins),
          materialize(compound.must(), joins),
          materialize(compound.mustNot(), joins),
          materialize(compound.should(), joins),
          compound.minimumShouldMatch(),
          compound.doesNotAffect());
    }
    return operator;
  }

  private static CompoundClause materialize(
      CompoundClause clause, Map<JoinOperator, MaterializedJoinOperator> joins) {
    return new CompoundClause(
        clause.operators().stream().map(child -> materialize(child, joins)).toList());
  }

  private static RuntimeException invalid(String message) {
    return Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException();
  }

  private static int localQueryCopies(SearchQuery query) {
    if (query instanceof CollectorQuery collector) {
      return switch (collector.collector()) {
        case FacetCollector facet -> 2 + facet.facetDefinitions().size();
        case AggregationCollector aggregation -> 2 + aggregation.facets().size();
      };
    }
    return 1;
  }

  private static RuntimeException exhausted(String message) {
    return Status.RESOURCE_EXHAUSTED.withDescription(message).asRuntimeException();
  }

  static void checkCancellation(Context context) {
    if (context.getDeadline() != null && context.getDeadline().isExpired()) {
      throw Status.DEADLINE_EXCEEDED.asRuntimeException();
    }
    if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
      throw Status.CANCELLED.asRuntimeException();
    }
  }

  final class Plan implements Closeable {
    private final boolean concurrent;
    private final int localQueryCopies;
    private final Map<String, GenerationId> dependencies = new LinkedHashMap<>();
    private final Map<String, QuerySnapshot> snapshots = new LinkedHashMap<>();
    @Nullable private QuerySnapshot localSnapshot;
    private SearchQuery query;
    private long bytes;
    private long work;
    private int keys;
    private boolean closed;

    private Plan(SearchQuery query) {
      this.concurrent = query.concurrent();
      this.query = query;
      this.localQueryCopies = localQueryCopies(query);
    }

    SearchQuery query() {
      return this.query;
    }

    QuerySnapshot snapshot() {
      return Objects.requireNonNull(this.localSnapshot);
    }

    boolean dependsOn(String id) {
      return this.dependencies.containsKey(id);
    }

    void activate(Context context) {
      checkCancellation(context);
      snapshot().setCancellationCheck(() -> checkCancellation(context));
    }

    void verifyDependencies() {
      for (var dependency : this.dependencies.entrySet()) {
        StandaloneAuthInterceptor.requireIndex(dependency.getKey());
        StandaloneJoinPlanner.this.runtime.catalog().getIndex(dependency.getKey());
        var live =
            StandaloneJoinPlanner.this
                .runtime
                .liveIndexes()
                .getIndexById(new ObjectId(dependency.getKey()));
        if (live.isEmpty() || !live.get().getGenerationId().equals(dependency.getValue())) {
          throw Status.NOT_FOUND
              .withDescription("Join dependency generation was retired")
              .asRuntimeException();
        }
      }
    }

    private void add(String id, Optional<GenerationId> expected) throws Exception {
      StandaloneAuthInterceptor.requireIndex(id);
      var desired = StandaloneJoinPlanner.this.runtime.catalog().getIndex(id);
      if (desired.getType() != IndexType.INDEX_TYPE_SEARCH) {
        throw invalid("Joins require search indexes");
      }
      if (this.dependencies.containsKey(id)) {
        return;
      }
      var generation =
          StandaloneJoinPlanner.this
              .runtime
              .liveIndexes()
              .getIndexById(new ObjectId(id))
              .filter(
                  index ->
                      StandaloneJoinPlanner.this
                          .runtime
                          .ingestion()
                          .isGenerationReady(index.getGenerationId()))
              .orElseThrow(
                  () ->
                      Status.UNAVAILABLE
                          .withDescription("Join index is not query-ready")
                          .asRuntimeException())
              .getGenerationId();
      if (expected.isPresent() && !expected.get().equals(generation)) {
        throw Status.FAILED_PRECONDITION
            .withDescription("Requested generation is no longer live")
            .asRuntimeException();
      }
      this.dependencies.put(id, generation);
      this.snapshots.put(
          id,
          StandaloneJoinPlanner.this.runtime.acquireQuerySnapshot(
              generation, expected.isPresent() && this.concurrent));
    }

    private void checkWork() {
      checkCancellation(Context.current());
      if (++this.work > MAX_FOREIGN_WORK) {
        throw exhausted("Join foreign-work limit exceeded");
      }
    }

    private void reserveKey(String key) {
      if (++this.keys > MAX_KEYS) {
        throw exhausted("Join distinct-key limit exceeded");
      }
      // Includes Java strings/hash entries, immutable lists, per-partition term queries and
      // rewrites.
      long estimate =
          (400L + 16L * key.getBytes(StandardCharsets.UTF_8).length)
              * (1L + (long) snapshot().partitions() * this.localQueryCopies);
      reserveBytes(estimate);
    }

    private void reserveBytes(long estimate) {
      if (this.bytes + estimate > MAX_QUERY_BYTES) {
        throw exhausted("Join query-memory limit exceeded");
      }
      long total = StandaloneJoinPlanner.this.retainedBytes.addAndGet(estimate);
      if (total > MAX_CONCURRENT_BYTES) {
        StandaloneJoinPlanner.this.retainedBytes.addAndGet(-estimate);
        throw exhausted("Concurrent join-memory limit exceeded");
      }
      this.bytes += estimate;
    }

    @Override
    public void close() throws IOException {
      if (!this.closed) {
        this.closed = true;
        try {
          IOUtils.close(this.snapshots.values());
        } finally {
          StandaloneJoinPlanner.this.retainedBytes.addAndGet(-this.bytes);
          this.snapshots.clear();
        }
      }
    }
  }
}
