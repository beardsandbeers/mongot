package com.xgen.mongot.index;

import com.google.common.truth.Truth;
import com.xgen.mongot.cursor.CursorConfig;
import com.xgen.testing.mongot.cursor.batch.BatchCursorOptionsBuilder;
import java.io.IOException;
import java.util.List;
import org.bson.BsonArray;
import org.junit.Test;

public class CountMergingBatchProducerTest {
  @Test
  public void testCount() throws IOException {
    var batchProducer =
        new CountMergingBatchProducer(
            List.of(new CountMetaBatchProducer(10), new CountMetaBatchProducer(20)));
    Truth.assertThat(batchProducer.isExhausted()).isFalse();

    batchProducer.execute(
        CursorConfig.DEFAULT_BSON_SIZE_SOFT_LIMIT, BatchCursorOptionsBuilder.empty());
    Truth.assertThat(batchProducer.isExhausted()).isTrue();

    BsonArray result = batchProducer.getNextBatch(CursorConfig.DEFAULT_BSON_SIZE_SOFT_LIMIT);
    BsonArray expected = CountMetaBatchProducer.getCountBatchResult(30);
    Truth.assertThat(result).isEqualTo(expected);
  }
}
