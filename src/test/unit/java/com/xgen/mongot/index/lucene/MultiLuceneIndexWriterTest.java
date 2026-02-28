package com.xgen.mongot.index.lucene;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.xgen.mongot.index.DocsExceededLimitsException;
import com.xgen.mongot.index.DocumentEvent;
import com.xgen.mongot.index.DocumentMetadata;
import com.xgen.mongot.index.EncodedUserData;
import com.xgen.mongot.index.FieldExceededLimitsException;
import com.xgen.mongot.index.lucene.util.LuceneDocumentIdEncoder;
import com.xgen.mongot.index.lucene.writer.MultiLuceneIndexWriter;
import com.xgen.mongot.index.lucene.writer.SingleLuceneIndexWriter;
import com.xgen.mongot.util.BsonUtils;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.lucene.util.BytesRef;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import org.bson.types.ObjectId;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.InOrder;

public class MultiLuceneIndexWriterTest {
  @Test
  public void testErrorDuringCreation() {
    Assert.assertThrows(
        IllegalStateException.class, () -> MultiLuceneIndexWriter.create(List.of()));
    Assert.assertThrows(
        IllegalStateException.class,
        () -> MultiLuceneIndexWriter.create(List.of(mock(SingleLuceneIndexWriter.class))));
  }

  @Test
  public void testUpdateIndex() throws Exception {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    for (int i = 0; i < 100; ++i) {
      ObjectId indexId = new ObjectId();
      RawBsonDocument document =
          BsonUtils.documentToRaw(
              new BsonDocument(indexId.toString(), new BsonDocument("_id", new BsonInt32(i))));
      DocumentEvent documentEvent =
          DocumentEvent.createInsert(
              DocumentMetadata.fromMetadataNamespace(Optional.of(document), indexId), document);
      multiWriter.updateIndex(documentEvent);
    }
    // Events are evenly distributed between two writers.
    verify(writer1, times(47)).updateIndex(any(), any());
    verify(writer2, times(53)).updateIndex(any(), any());
  }

  @Test
  public void testCommit() throws Exception {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    InOrder inOrder = inOrder(writer1, writer2);
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    var userData = EncodedUserData.fromString("{\"foo\":\"bar\"}");
    multiWriter.commit(userData);
    // LuceneIndexWriters are committed in the reverse order.
    inOrder.verify(writer2).commit(userData);
    inOrder.verify(writer1).commit(userData);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testGetCommitUserData() {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    var userData1 = EncodedUserData.fromString("{\"writer\":\"one\"}");
    var userData2 = EncodedUserData.fromString("{\"writer\":\"two\"}");
    when(writer1.getCommitUserData()).thenReturn(userData1);
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    when(writer2.getCommitUserData()).thenReturn(userData2);
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    // Returns userData from the first writer.
    Assert.assertEquals(userData1, multiWriter.getCommitUserData());
  }

  @Test
  public void testExceededLimits_noException() {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    when(writer1.exceededFieldLimits()).thenReturn(Optional.empty());
    when(writer1.exceededDocsLimits()).thenReturn(Optional.empty());
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    when(writer2.exceededFieldLimits()).thenReturn(Optional.empty());
    when(writer2.exceededDocsLimits()).thenReturn(Optional.empty());
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    Assert.assertEquals(Optional.empty(), multiWriter.exceededLimits());
  }

  @Test
  public void testExceededLimits_fieldLimitsException() {
    Optional<FieldExceededLimitsException> exceededLimitsException =
        Optional.of(new FieldExceededLimitsException("foo"));
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    when(writer1.exceededFieldLimits()).thenReturn(exceededLimitsException);
    when(writer1.exceededDocsLimits()).thenReturn(Optional.empty());
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    when(writer2.exceededFieldLimits()).thenReturn(Optional.empty());
    when(writer2.exceededDocsLimits()).thenReturn(Optional.empty());
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    Assert.assertEquals(exceededLimitsException, multiWriter.exceededLimits());
  }

  @Test
  public void testExceededLimits_docLimitsException() {
    Optional<DocsExceededLimitsException> exceededLimitsException =
        Optional.of(new DocsExceededLimitsException("foo"));
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    when(writer1.exceededFieldLimits()).thenReturn(Optional.empty());
    when(writer1.exceededDocsLimits()).thenReturn(Optional.empty());
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    when(writer2.exceededFieldLimits()).thenReturn(Optional.empty());
    when(writer2.exceededDocsLimits()).thenReturn(exceededLimitsException);
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    Assert.assertEquals(exceededLimitsException, multiWriter.exceededLimits());
  }

  @Test
  public void testExceededLimits_multipleLimitsException() {
    Optional<FieldExceededLimitsException> exceededLimitsException1 =
        Optional.of(new FieldExceededLimitsException("foo"));
    Optional<DocsExceededLimitsException> exceededLimitsException2 =
        Optional.of(new DocsExceededLimitsException("bar"));
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    when(writer1.exceededFieldLimits()).thenReturn(Optional.empty());
    when(writer1.exceededDocsLimits()).thenReturn(exceededLimitsException2);
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    when(writer2.exceededFieldLimits()).thenReturn(exceededLimitsException1);
    when(writer2.exceededDocsLimits()).thenReturn(Optional.empty());
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    Assert.assertEquals(exceededLimitsException1, multiWriter.exceededLimits());
  }

  @Test
  public void testDeleteAll() throws Exception {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    InOrder inOrder = inOrder(writer1, writer2);
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    EncodedUserData userData = EncodedUserData.fromString("{\"foo\":\"bar\"}");
    multiWriter.deleteAll(userData);
    // LuceneIndexWriters are deleted in the sequential order.
    inOrder.verify(writer1).deleteAll(userData);
    inOrder.verify(writer2).deleteAll(userData);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void testClose() throws Exception {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    // LuceneIndexWriters are closed. Order doesn't matter.
    multiWriter.close();
    verify(writer1).close();
    verify(writer2).close();
  }

  @Test
  public void testGetNumFields() throws Exception {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    when(writer1.getFieldNames()).thenReturn(Set.of("foo", "bar"));
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    when(writer2.getFieldNames()).thenReturn(Set.of("bar", "baz"));
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    Assert.assertEquals(3, multiWriter.getNumFields());
  }

  @Test
  public void testGetNumDocs() throws Exception {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    when(writer1.getNumDocs()).thenReturn(1500000000L);
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    when(writer2.getNumDocs()).thenReturn(2000000000L);
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    Assert.assertEquals(3500000000L, multiWriter.getNumDocs());
  }

  @Test
  public void testGetNumLuceneMaxDocs() throws Exception {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    when(writer1.getNumLuceneMaxDocs()).thenReturn(2L);
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    when(writer2.getNumLuceneMaxDocs()).thenReturn(3L);
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    Assert.assertEquals(5L, multiWriter.getNumLuceneMaxDocs());
  }

  @Test
  public void testGetMaxLuceneMaxDocs() throws Exception {
    SingleLuceneIndexWriter writer1 = mock(SingleLuceneIndexWriter.class);
    when(writer1.getMaxLuceneMaxDocs()).thenReturn(2);
    SingleLuceneIndexWriter writer2 = mock(SingleLuceneIndexWriter.class);
    when(writer2.getMaxLuceneMaxDocs()).thenReturn(3);
    MultiLuceneIndexWriter multiWriter = MultiLuceneIndexWriter.create(List.of(writer1, writer2));
    Assert.assertEquals(3, multiWriter.getMaxLuceneMaxDocs());
  }

  // The old way of computing index partition ID
  private int getOldIndexPartitionId(byte[] encodedDocumentId, int numPartitions) {
    // A random Prime number to avoid uneven distribution due to hash conflicts.
    int hashSeed = 1867391047;
    // The actual indexPartition will be decided by the following hash function.
    HashFunction hashFunction = Hashing.murmur3_128(hashSeed);
    return (int)
        (Integer.toUnsignedLong(hashFunction.hashBytes(encodedDocumentId).asInt()) % numPartitions);
  }

  // Generates random encoded document IDs for testing.
  private byte[] generateRandomEncodedDocumentId(Random random) {
    // Generate different types of BSON document IDs
    switch (random.nextInt(4)) {
      case 0 -> {
        byte[] objectIdBytes = new byte[12];
        random.nextBytes(objectIdBytes);
        return LuceneDocumentIdEncoder.encodeDocumentId(
            new BsonObjectId(new ObjectId(objectIdBytes)));
      }
      case 1 -> {
        String randomString = generateRandomString(random, random.nextInt(50) + 1);
        return LuceneDocumentIdEncoder.encodeDocumentId(new BsonString(randomString));
      }
      case 2 -> {
        int randomInt = random.nextInt();
        return LuceneDocumentIdEncoder.encodeDocumentId(new BsonInt32(randomInt));
      }
      case 3 -> {
        long randomLong = random.nextLong();
        return LuceneDocumentIdEncoder.encodeDocumentId(new BsonInt64(randomLong));
      }
      default -> throw new IllegalStateException("Unexpected case");
    }
  }

  private String generateRandomString(Random random, int length) {
    StringBuilder sb = new StringBuilder(length);
    String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    for (int i = 0; i < length; i++) {
      sb.append(chars.charAt(random.nextInt(chars.length())));
    }
    return sb.toString();
  }

  @Test
  public void testGetIndexPartitionIdBackwardCompatibility() {
    // Note: override with a seed to reproduce a failure.
    long seed = ThreadLocalRandom.current().nextLong();
    System.out.println("Testing with seed: " + seed);
    Random random = new Random(seed);
    int[] partitionCounts = {2, 4, 8, 16, 32};

    for (int numPartitions : partitionCounts) {
      for (int i = 0; i < 100; i++) { // Test 100 random document IDs per partition count
        // Generate random document ID
        byte[] encodedDocumentId = generateRandomEncodedDocumentId(random);
        BytesRef bytesRef = new BytesRef(encodedDocumentId);

        // Compute partition ID using old method
        int oldPartitionId = getOldIndexPartitionId(encodedDocumentId, numPartitions);

        // Compute partition ID using new method
        int newPartitionId = MultiLuceneIndexWriter.getIndexPartitionId(bytesRef, numPartitions);

        Assert.assertEquals(
            String.format(
                "Partition mapping changed for encodedId (length=%d)=%s, numPartitions=%d",
                encodedDocumentId.length,
                HexFormat.of().formatHex(encodedDocumentId),
                numPartitions),
            oldPartitionId,
            newPartitionId);
      }
    }
  }
}
