package com.xgen.mongot.index.lucene;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.Optional;
import org.junit.Test;

public class EmptySearchBatchProducerTest {
  @Test
  public void testCloseDestroysSearcherReference() throws IOException {
    var mockSearcherReference = mock(LuceneIndexSearcherReference.class);
    var batchProducer = new EmptySearchBatchProducer(Optional.of(mockSearcherReference));
    batchProducer.close();
    batchProducer.close();
    verify(mockSearcherReference, times(1)).close();
  }
}
