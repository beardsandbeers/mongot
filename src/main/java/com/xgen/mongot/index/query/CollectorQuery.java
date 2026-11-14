package com.xgen.mongot.index.query;

import com.xgen.mongot.index.query.collectors.AggregationCollector;
import com.xgen.mongot.index.query.collectors.Collector;
import com.xgen.mongot.index.query.collectors.FacetCollector;
import com.xgen.mongot.index.query.counts.Count;
import com.xgen.mongot.index.query.highlights.UnresolvedHighlight;
import com.xgen.mongot.index.query.operators.Operator;
import com.xgen.mongot.index.query.sort.SortSpec;
import com.xgen.mongot.util.Check;
import java.util.Optional;
import org.bson.BsonDocument;

public record CollectorQuery(
    Collector collector,
    String index,
    Count count,
    Optional<UnresolvedHighlight> highlight,
    Optional<Pagination> pagination,
    boolean returnStoredSource,
    boolean scoreDetails,
    boolean concurrent,
    Optional<SortSpec> rawSortSpec,
    Optional<Tracking> tracking,
    Optional<ReturnScope> returnScope)
    implements SearchQuery {

  public CollectorQuery {
    Check.argNotEmpty(index, "index");
    Check.argNotNull(collector, "collector");
  }

  @Override
  public BsonDocument queryToBson() {
    return this.collector.toBson();
  }

  public CollectorQuery withOperator(Operator operator) {
    Collector materialized =
        switch (this.collector) {
          case FacetCollector facet -> facet.withOperator(operator);
          case AggregationCollector aggregation ->
              new AggregationCollector(operator, aggregation.facets(), aggregation.sortSpec());
        };
    return new CollectorQuery(
        materialized,
        this.index,
        this.count,
        this.highlight,
        this.pagination,
        this.returnStoredSource,
        this.scoreDetails,
        this.concurrent,
        this.rawSortSpec,
        this.tracking,
        this.returnScope);
  }
}
