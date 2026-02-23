package com.xgen.mongot.index.lucene;

import com.google.common.truth.Truth;
import com.xgen.mongot.cursor.CursorConfig;
import com.xgen.mongot.index.CountMetaBatchProducer;
import com.xgen.testing.mongot.cursor.batch.BatchCursorOptionsBuilder;
import java.io.IOException;
import org.junit.Test;

public class CountMetaBatchProducerTest {
  @Test
  public void testCountProduced() throws IOException {
    var batchProducer = new CountMetaBatchProducer(10);
    Truth.assertThat(batchProducer.isExhausted()).isFalse();

    batchProducer.execute(
        CursorConfig.DEFAULT_BSON_SIZE_SOFT_LIMIT, BatchCursorOptionsBuilder.empty());
    Truth.assertThat(batchProducer.isExhausted()).isTrue();
  }
}
