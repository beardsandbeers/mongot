package com.xgen.mongot.index.lucene;

import static com.xgen.mongot.index.lucene.VectorIndexingAndQueryingTestHarness.MOCK_VECTOR_INDEX_DEFINITION_FOR_BIT_VECTORS;
import static com.xgen.mongot.index.lucene.VectorIndexingAndQueryingTestHarness.MOCK_VECTOR_INDEX_DEFINITION_FOR_FLOAT_BYTE_VECTORS;
import static com.xgen.mongot.util.bson.FloatVector.OriginalType.NATIVE;

import com.xgen.mongot.util.bson.Vector;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      VectorIndexingAndQueryingTest.FloatAndByteVectorTests.class,
      VectorIndexingAndQueryingTest.TestBitVectors.class,
    })
public class VectorIndexingAndQueryingTest {

  public static class FloatAndByteVectorTests {
    @Test
    public void testSingleDocumentWithFloatVector() throws Exception {
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      float[][] singleVector = new float[][] {{1f, 0f, 0f}};
      var docs =
          testHarness.createVectorDocs(
              singleVector, 0, VectorIndexingAndQueryingTestHarness::fromNativeFloats);
      Vector queryVector = Vector.fromFloats(new float[] {0.9f, 0f, 0f}, NATIVE);
      List<Integer> expectedDocIds = List.of(0);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_FLOAT_BYTE_VECTORS);
      testHarness.runTest(docs, queryVector, expectedDocIds);
    }

    @Test
    public void testMultiDocumentsWithFloatVector() throws Exception {
      // each vector has successively decreasing cosine similarity scores with the query vector
      float[][] floatVectors = {
        {1f, 0f, 0f},
        {0.9f, 0.1f, 0f},
        {0.8f, 0.2f, 0f},
        {0.7f, 0.3f, 0f},
        {0.6f, 0.4f, 0f},
        {0.5f, 0.5f, 0f},
        {0.4f, 0.6f, 0f},
        {0.3f, 0.7f, 0f},
        {0.2f, 0.8f, 0f},
        {0.1f, 0.9f, 0f}
      };
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      var docs =
          testHarness.createVectorDocs(
              floatVectors, 0, VectorIndexingAndQueryingTestHarness::fromNativeFloats);
      Vector queryVector = Vector.fromFloats(new float[] {0.9f, 0f, 0f}, NATIVE);
      List<Integer> expectedDocIds = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_FLOAT_BYTE_VECTORS);
      testHarness.runTest(docs, queryVector, expectedDocIds);
    }

    @Test
    public void testMultiDocumentsWithFloatVectorAndQueryVectorFromDocs() throws Exception {
      // each vector has successively decreasing cosine similarity scores with the query vector
      float[][] floatVectors = {
        {1f, 0f, 0f},
        {0.9f, 0.1f, 0f},
        {0.8f, 0.2f, 0f},
        {0.7f, 0.3f, 0f},
        {0.6f, 0.4f, 0f},
        {0.5f, 0.5f, 0f},
        {0.4f, 0.6f, 0f},
        {0.3f, 0.7f, 0f},
        {0.2f, 0.8f, 0f},
        {0.1f, 0.9f, 0f}
      };
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      var docs =
          testHarness.createVectorDocs(
              floatVectors, 0, VectorIndexingAndQueryingTestHarness::fromNativeFloats);
      Vector queryVector = Vector.fromFloats(floatVectors[0], NATIVE);
      List<Integer> expectedDocIds = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_FLOAT_BYTE_VECTORS);
      testHarness.runTest(docs, queryVector, expectedDocIds);
    }

    @Test
    public void testSingleDocumentWithByteVector() throws Exception {
      byte[][] singeVector = new byte[][] {{(byte) 0x01, (byte) 0x00, (byte) 0x00}};
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      var docs = testHarness.createVectorDocs(singeVector, 0, Vector::fromBytes);
      Vector queryVector = Vector.fromBytes(new byte[] {(byte) 0x02, (byte) 0x00, (byte) 0x00});
      List<Integer> expectedDocIds = List.of(0);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_FLOAT_BYTE_VECTORS);
      testHarness.runTest(docs, queryVector, expectedDocIds);
    }

    @Test
    public void testMultiDocumentsWithByteVector() throws Exception {
      byte[][] byteVectors = {
        {(byte) 0x01, (byte) 0x00, (byte) 0x00},
        {(byte) 0x02, (byte) 0x01, (byte) 0x00},
        {(byte) 0x03, (byte) 0x02, (byte) 0x01},
        {(byte) 0x04, (byte) 0x03, (byte) 0x02},
        {(byte) 0x05, (byte) 0x04, (byte) 0x03}
      };
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      var docs = testHarness.createVectorDocs(byteVectors, 0, Vector::fromBytes);
      Vector queryVector = Vector.fromBytes(new byte[] {(byte) 0x02, (byte) 0x00, (byte) 0x00});
      List<Integer> expectedDocIds = List.of(0, 1, 2, 3, 4);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_FLOAT_BYTE_VECTORS);
      testHarness.runTest(docs, queryVector, expectedDocIds);
    }

    @Test
    public void testMultiDocumentsWithByteVectorAndQueryVectorFromDocs() throws Exception {
      byte[][] byteVectors = {
        {(byte) 0x01, (byte) 0x00, (byte) 0x00},
        {(byte) 0x02, (byte) 0x01, (byte) 0x00},
        {(byte) 0x03, (byte) 0x02, (byte) 0x01},
        {(byte) 0x04, (byte) 0x03, (byte) 0x02},
        {(byte) 0x05, (byte) 0x04, (byte) 0x03}
      };
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      var docs = testHarness.createVectorDocs(byteVectors, 0, Vector::fromBytes);
      Vector queryVector = Vector.fromBytes(byteVectors[0]);
      List<Integer> expectedDocIds = List.of(0, 1, 2, 3, 4);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_FLOAT_BYTE_VECTORS);
      testHarness.runTest(docs, queryVector, expectedDocIds);
    }

    @Test
    public void testQueryWithMixedTypes() throws Exception {
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      byte[][] byteVectors = {
        {(byte) 0x01, (byte) 0x00, (byte) 0x00},
        {(byte) 0x02, (byte) 0x01, (byte) 0x00},
        {(byte) 0x03, (byte) 0x02, (byte) 0x01},
        {(byte) 0x04, (byte) 0x03, (byte) 0x02},
        {(byte) 0x05, (byte) 0x04, (byte) 0x03}
      };
      var byteVectorDocs = testHarness.createVectorDocs(byteVectors, 0, Vector::fromBytes);
      Vector byteQueryVector = Vector.fromBytes(new byte[] {(byte) 0x02, (byte) 0x00, (byte) 0x00});
      List<Integer> expectedByteVectorDocIds = List.of(0, 1, 2, 3, 4);

      float[][] floatVectors = {
        {1f, 0f, 0f},
        {0.9f, 0.1f, 0f},
        {0.8f, 0.2f, 0f},
        {0.7f, 0.3f, 0f},
        {0.6f, 0.4f, 0f},
        {0.5f, 0.5f, 0f},
        {0.4f, 0.6f, 0f},
        {0.3f, 0.7f, 0f},
        {0.2f, 0.8f, 0f},
        {0.1f, 0.9f, 0f}
      };
      int floatVectorsStartDocId = byteVectors.length;
      var floatVectorDocs =
          testHarness.createVectorDocs(
              floatVectors,
              floatVectorsStartDocId,
              VectorIndexingAndQueryingTestHarness::fromNativeFloats);
      Vector floatQueryVector = Vector.fromFloats(new float[] {0.9f, 0f, 0f}, NATIVE);
      List<Integer> expectedFloatVectorDocIds =
          IntStream.range(0, floatVectors.length)
              .mapToObj(i -> floatVectorsStartDocId + i)
              .toList();

      var docsToIndex = Stream.of(byteVectorDocs, floatVectorDocs).flatMap(List::stream).toList();
      var queriesAndExpectedDocIds =
          Map.of(
              byteQueryVector,
              expectedByteVectorDocIds,
              floatQueryVector,
              expectedFloatVectorDocIds);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_FLOAT_BYTE_VECTORS);
      testHarness.runMultiQueryTest(docsToIndex, queriesAndExpectedDocIds);

      // additional validations for mixed types in same field
      var floatQueryResult = testHarness.runVectorSearchQuery(floatQueryVector, false);
      List<Integer> byteVectorDocIdsInFloatQueryResult =
          floatQueryResult.stream()
              .map(e -> testHarness.getDocId(e.asDocument()))
              .filter(expectedByteVectorDocIds::contains)
              .toList();
      Assert.assertTrue(byteVectorDocIdsInFloatQueryResult.isEmpty());

      var byteQueryResult = testHarness.runVectorSearchQuery(byteQueryVector, false);
      List<Integer> floatVectorDocIdsInByteQueryResult =
          byteQueryResult.stream()
              .map(e -> testHarness.getDocId(e.asDocument()))
              .filter(expectedFloatVectorDocIds::contains)
              .toList();
      Assert.assertTrue(floatVectorDocIdsInByteQueryResult.isEmpty());
    }

    @Test
    public void testMultiDocumentsWithByteVectorExactSearch() throws Exception {
      byte[][] byteVectors = {
        {(byte) 0x01, (byte) 0x00, (byte) 0x00},
        {(byte) 0x02, (byte) 0x01, (byte) 0x00},
        {(byte) 0x03, (byte) 0x02, (byte) 0x01},
        {(byte) 0x04, (byte) 0x03, (byte) 0x02},
        {(byte) 0x05, (byte) 0x04, (byte) 0x03}
      };
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      var docs = testHarness.createVectorDocs(byteVectors, 0, Vector::fromBytes);
      Vector queryVector = Vector.fromBytes(new byte[] {(byte) 0x02, (byte) 0x00, (byte) 0x00});
      List<Integer> expectedDocIds = List.of(0, 1, 2, 3, 4);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_FLOAT_BYTE_VECTORS);
      testHarness.runTest(docs, queryVector, expectedDocIds, true);
      // Also test approximate vector search and confirm results are the same
      testHarness.runTest(docs, queryVector, expectedDocIds, false);
    }

    @Test
    public void testMultiDocumentsWithBitVectorExactSearch() throws Exception {
      byte[][] randomBitVectors = {
        {(byte) 0b10010000},
        {(byte) 0b01000100},
        {(byte) 0b00100100},
        {(byte) 0b10010000},
        {(byte) 0b00011000},
        {(byte) 0b10110100},
        {(byte) 0b00000000},
        {(byte) 0b11111111},
      };
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      var docs = testHarness.createVectorDocs(randomBitVectors, 0, Vector::fromBits);
      Vector queryVector = Vector.fromBits(new byte[] {(byte) 0b10101010});
      List<Integer> expectedDocIds = List.of(0, 2, 3, 4, 5, 6, 7, 1);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_BIT_VECTORS);
      testHarness.runTest(docs, queryVector, expectedDocIds, true);
      // Also test approximate vector search and confirm results are the same
      testHarness.runTest(docs, queryVector, expectedDocIds, false);
    }
  }

  @RunWith(Parameterized.class)
  public static class TestBitVectors {
    private final byte[][] testVectors;
    private final Vector queryVector;
    private final List<Integer> expectedDocIds;

    public TestBitVectors(byte[][] testVectors, Vector queryVector, List<Integer> expectedDocIds) {
      this.testVectors = testVectors;
      this.queryVector = queryVector;
      this.expectedDocIds = expectedDocIds;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
      byte[][] singleBitVector = {
        {(byte) 0b10010000},
      };
      byte[][] multipleBitVectors = {
        {(byte) 0b10000000}, {(byte) 0b01000000}, {(byte) 0b00010000}, {(byte) 0b00001000}
      };
      byte[][] randomBitVectors = {
        {(byte) 0b10010000},
        {(byte) 0b01000100},
        {(byte) 0b00100100},
        {(byte) 0b10010000},
        {(byte) 0b00011000},
        {(byte) 0b10110100},
        {(byte) 0b00000000},
        {(byte) 0b11111111},
      };
      return Arrays.asList(
          new Object[][] {
            // single vector test case
            {singleBitVector, Vector.fromBits(new byte[] {(byte) 0b10010000}), List.of(0)},

            // multi vector test cases
            {
              multipleBitVectors,
              Vector.fromBits(new byte[] {(byte) 0b01000000}),
              List.of(1, 0, 2, 3)
            },
            {
              multipleBitVectors,
              Vector.fromBits(new byte[] {(byte) 0b00000001}),
              List.of(0, 1, 2, 3)
            },

            // advanced test cases
            // for each query vector below, the expected doc ids are computed using numpy
            {
              randomBitVectors,
              Vector.fromBits(new byte[] {(byte) 0b10000000}),
              List.of(0, 3, 6, 1, 2, 4, 5, 7)
            },
            {
              randomBitVectors,
              Vector.fromBits(new byte[] {(byte) 0b10010000}),
              List.of(0, 3, 4, 5, 6, 1, 2, 7)
            },
            {
              randomBitVectors,
              Vector.fromBits(new byte[] {(byte) 0b10101010}),
              List.of(0, 2, 3, 4, 5, 6, 7, 1)
            },
            {
              randomBitVectors,
              Vector.fromBits(new byte[] {(byte) 0b11000011}),
              List.of(0, 1, 3, 6, 7, 2, 4, 5)
            },
            {
              randomBitVectors,
              Vector.fromBits(new byte[] {(byte) 0b11111111}),
              List.of(7, 5, 0, 1, 2, 3, 4, 6)
            },
            {
              randomBitVectors,
              Vector.fromBits(new byte[] {(byte) 0b00000000}),
              List.of(6, 0, 1, 2, 3, 4, 5, 7)
            }
          });
    }

    @Test
    public void testBitVectors() throws Exception {
      var testHarness = new VectorIndexingAndQueryingTestHarness();
      var docs = testHarness.createVectorDocs(this.testVectors, 0, Vector::fromBits);
      testHarness.setUp(MOCK_VECTOR_INDEX_DEFINITION_FOR_BIT_VECTORS);
      testHarness.runTest(docs, this.queryVector, this.expectedDocIds);
    }
  }
}
