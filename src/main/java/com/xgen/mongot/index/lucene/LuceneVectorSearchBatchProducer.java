package com.xgen.mongot.index.lucene;

import static com.xgen.mongot.util.Check.checkState;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.cursor.batch.BatchCursorOptions;
import com.xgen.mongot.cursor.batch.BatchSizeStrategy;
import com.xgen.mongot.index.BatchProducer;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.VectorSearchResult;
import com.xgen.mongot.util.Bytes;
import com.xgen.mongot.util.bson.BsonArrayBuilder;
import java.io.IOException;
import java.util.List;
import org.bson.BsonArray;
import org.bson.RawBsonDocument;

/**
 * Unlike LuceneSearchBatchProducer, this class must start with all results pre-fetched, since
 * vector search does not support efficient pagination. Serialization into BSON batches is done
 * lazily as batches are requested.
 */
public class LuceneVectorSearchBatchProducer implements BatchProducer {

  private final List<VectorSearchResult> allResults;
  private final IndexMetricsUpdater.QueryingMetricsUpdater metricsUpdater;

  private final BatchSizeStrategy batchSizeStrategy;

  @Var private int nextResultIndex = 0;
  @Var private int nextBatchDocumentMax = BatchSizeStrategy.MAXIMUM_BATCH_SIZE;
  @Var private boolean closed = false;

  LuceneVectorSearchBatchProducer(
      List<VectorSearchResult> allResults,
      IndexMetricsUpdater.QueryingMetricsUpdater metricsUpdater,
      BatchSizeStrategy batchSizeStrategy) {
    this.allResults = allResults;
    this.metricsUpdater = metricsUpdater;
    this.batchSizeStrategy = batchSizeStrategy;
  }

  @Override
  public void execute(Bytes sizeLimit, BatchCursorOptions queryCursorOptions) throws IOException {
    // All results are pre-fetched for vector queries but those results could be large so we still
    // follow cursor semantics for returning those results to the client.
    this.nextBatchDocumentMax = this.batchSizeStrategy.adviseNextBatchSize();
  }

  @Override
  public boolean isExhausted() {
    return this.nextResultIndex >= this.allResults.size();
  }

  @Override
  public final BsonArray getNextBatch(Bytes sizeLimit) throws IOException {
    if (this.closed) {
      throw new IOException(
          "internal error: attempted getNextBatch on closed LuceneVectorSearchBatchProducer");
    }

    var builder = BsonArrayBuilder.withLimit(sizeLimit);

    while (this.nextResultIndex < this.allResults.size()
        && builder.getDocumentCount() < this.nextBatchDocumentMax) {
      VectorSearchResult result = this.allResults.get(this.nextResultIndex);
      RawBsonDocument resultBson = result.toRawBson();

      if (!builder.append(resultBson)) {
        break; // The next result will be left for the next batch.
      }

      ++this.nextResultIndex;
    }

    var batch = builder.build();

    this.metricsUpdater.getBatchDocumentCount().record(builder.getDocumentCount());
    this.metricsUpdater.getBatchDataSize().record(builder.getDataSize().toBytes());

    checkState(
        this.nextResultIndex >= this.allResults.size() || builder.getDocumentCount() > 0,
        "Search result output exceeds BSON size limit");

    return batch;
  }

  @Override
  public void close() throws IOException {
    if (this.closed) {
      return;
    }

    this.closed = true;
  }
}
