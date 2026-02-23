package com.xgen.mongot.index;

import static org.mockito.Mockito.mock;

import com.xgen.mongot.util.BsonUtils;
import com.xgen.testing.mongot.index.IndexMetricsUpdaterBuilder;
import com.xgen.testing.mongot.mock.index.SearchIndex;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.RawBsonDocument;
import org.bson.types.ObjectId;
import org.junit.Assert;
import org.junit.Test;

public class MeteredIndexWriterTest {
  @Test
  public void testMetrics() throws Exception {
    IndexMetricsUpdater.IndexingMetricsUpdater indexingMetricsUpdater =
        IndexMetricsUpdaterBuilder.IndexingMetricsUpdaterBuilder.builder()
            .metricsFactory(SearchIndex.mockMetricsFactory())
            .build();

    MeteredIndexWriter writer =
        new MeteredIndexWriter(mock(IndexWriter.class), indexingMetricsUpdater);
    ObjectId indexId = new ObjectId();
    RawBsonDocument doc1 =
        BsonUtils.documentToRaw(
            new BsonDocument()
                .append(indexId.toString(), new BsonDocument("_id", new BsonInt32(1))));
    long doc1BytesSize = doc1.getByteBuffer().remaining();

    writer.updateIndex(
        DocumentEvent.createInsert(
            DocumentMetadata.fromMetadataNamespace(Optional.of(doc1), indexId), doc1));
    writer.commit(EncodedUserData.EMPTY);
    Assert.assertEquals(
        1,
        indexingMetricsUpdater.getDocumentEventTypeCounter(DocumentEvent.EventType.INSERT).count(),
        0);
    Assert.assertEquals(
        0,
        indexingMetricsUpdater.getDocumentEventTypeCounter(DocumentEvent.EventType.UPDATE).count(),
        0);
    Assert.assertEquals(
        0,
        indexingMetricsUpdater.getDocumentEventTypeCounter(DocumentEvent.EventType.DELETE).count(),
        0);
    Assert.assertEquals(1, indexingMetricsUpdater.getCommitTimer().count());
    Assert.assertEquals(
        doc1BytesSize, indexingMetricsUpdater.getTotalBytesProcessedCounter().count(), 0);

    RawBsonDocument doc2 =
        BsonUtils.documentToRaw(
            new BsonDocument()
                .append(indexId.toString(), new BsonDocument("_id", new BsonInt32(1))));
    long docs1And2BytesSize = doc1BytesSize + doc2.getByteBuffer().remaining();

    writer.updateIndex(
        DocumentEvent.createUpdate(
            DocumentMetadata.fromMetadataNamespace(Optional.of(doc2), indexId), doc2));
    writer.commit(EncodedUserData.EMPTY);
    Assert.assertEquals(
        1,
        indexingMetricsUpdater.getDocumentEventTypeCounter(DocumentEvent.EventType.INSERT).count(),
        0);
    Assert.assertEquals(
        1,
        indexingMetricsUpdater.getDocumentEventTypeCounter(DocumentEvent.EventType.UPDATE).count(),
        0);
    Assert.assertEquals(
        0,
        indexingMetricsUpdater.getDocumentEventTypeCounter(DocumentEvent.EventType.DELETE).count(),
        0);
    Assert.assertEquals(2, indexingMetricsUpdater.getCommitTimer().count());
    Assert.assertEquals(
        docs1And2BytesSize, indexingMetricsUpdater.getTotalBytesProcessedCounter().count(), 0);

    writer.updateIndex(DocumentEvent.createDelete(new BsonInt32(3)));
    writer.commit(EncodedUserData.EMPTY);
    writer.close();
    Assert.assertEquals(
        1,
        indexingMetricsUpdater.getDocumentEventTypeCounter(DocumentEvent.EventType.INSERT).count(),
        0);
    Assert.assertEquals(
        1,
        indexingMetricsUpdater.getDocumentEventTypeCounter(DocumentEvent.EventType.UPDATE).count(),
        0);
    Assert.assertEquals(
        1,
        indexingMetricsUpdater.getDocumentEventTypeCounter(DocumentEvent.EventType.DELETE).count(),
        0);
    Assert.assertEquals(3, indexingMetricsUpdater.getCommitTimer().count());
    // The counter should not record deletes
    Assert.assertEquals(
        docs1And2BytesSize, indexingMetricsUpdater.getTotalBytesProcessedCounter().count(), 0);
  }
}
