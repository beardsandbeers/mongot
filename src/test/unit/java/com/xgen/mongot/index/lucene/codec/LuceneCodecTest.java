package com.xgen.mongot.index.lucene.codec;

import static com.xgen.mongot.index.lucene.field.FieldName.TypeField.KNN_BIT;
import static com.xgen.mongot.index.lucene.field.FieldName.TypeField.KNN_BYTE;
import static com.xgen.mongot.index.lucene.field.FieldName.TypeField.KNN_F32_Q1;
import static com.xgen.mongot.index.lucene.field.FieldName.TypeField.KNN_F32_Q7;
import static com.xgen.mongot.index.lucene.field.FieldName.TypeField.KNN_VECTOR;
import static com.xgen.mongot.index.lucene.field.FieldName.TypeField.NUMBER_INT64;
import static org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH;
import static org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN;
import static org.junit.Assert.assertThrows;

import com.xgen.mongot.index.definition.VectorFieldSpecification;
import com.xgen.mongot.index.definition.VectorIndexingAlgorithm;
import com.xgen.mongot.index.definition.VectorQuantization;
import com.xgen.mongot.index.definition.VectorSimilarity;
import com.xgen.mongot.index.lucene.codec.flat.Float32AndByteFlatVectorsFormat;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.lucene.quantization.Mongot01042HnswBinaryQuantizedVectorsFormat;
import com.xgen.mongot.index.lucene.quantization.Mongot01042HnswBitVectorsFormat;
import com.xgen.mongot.util.FieldPath;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class LuceneCodecTest {

  public record TestSetup(
      FieldName.TypeField typeField,
      Class<? extends KnnVectorsFormat> expectedFormatClass,
      int maxEdges,
      int numEdgeCandidates) {}

  @DataPoints
  public static final List<TestSetup> data =
      List.of(
          new TestSetup(KNN_VECTOR, Lucene99HnswVectorsFormat.class, 32, 200),
          new TestSetup(KNN_BYTE, Lucene99HnswVectorsFormat.class, 128, 100),
          new TestSetup(KNN_BIT, Mongot01042HnswBitVectorsFormat.class, 512, 1000),
          new TestSetup(KNN_F32_Q7, Lucene99HnswScalarQuantizedVectorsFormat.class, 2, 30),
          new TestSetup(KNN_F32_Q1, Mongot01042HnswBinaryQuantizedVectorsFormat.class, 49, 66));

  @Theory
  public void getKnnVectorsFormatForField_withProvidedSetup_producesExpectedFormat(TestSetup setup)
      throws NoSuchFieldException, IllegalAccessException {

    FieldPath path = FieldPath.parse("vector");
    VectorFieldSpecification specification =
        createVectorFieldDefinition(setup.maxEdges(), setup.numEdgeCandidates());

    LuceneCodec codec = new LuceneCodec(Map.of(path, specification));
    PerFieldKnnVectorsFormat format = (PerFieldKnnVectorsFormat) codec.knnVectorsFormat();
    KnnVectorsFormat vectorsFormat =
        format.getKnnVectorsFormatForField(
            setup.typeField().getLuceneFieldName(path, Optional.empty()));

    Assert.assertTrue(setup.expectedFormatClass().isInstance(vectorsFormat));
    VectorFieldSpecification.HnswOptions hnswOptions =
        getKnnFieldConfig(setup.expectedFormatClass(), vectorsFormat);
    Assert.assertEquals(setup.maxEdges(), hnswOptions.maxEdges());
    Assert.assertEquals(setup.numEdgeCandidates(), hnswOptions.numEdgeCandidates());
  }

  @Test
  public void getKnnVectorsFormatForField_withWrongType_producesDefaultFormat() {
    FieldPath path = FieldPath.parse("vector");
    VectorFieldSpecification specification = createVectorFieldDefinition(48, 566);

    LuceneCodec codec = new LuceneCodec(Map.of(path, specification));
    PerFieldKnnVectorsFormat format = (PerFieldKnnVectorsFormat) codec.knnVectorsFormat();

    assertThrows(
        IllegalStateException.class,
        () ->
            format.getKnnVectorsFormatForField(
                NUMBER_INT64.getLuceneFieldName(path, Optional.empty())));
  }

  @Test
  public void getKnnVectorsFormatForField_withUnknownPath_producesDefaultFormat()
      throws NoSuchFieldException, IllegalAccessException {
    FieldPath path = FieldPath.parse("vector");

    LuceneCodec codec = new LuceneCodec();
    PerFieldKnnVectorsFormat format = (PerFieldKnnVectorsFormat) codec.knnVectorsFormat();
    KnnVectorsFormat vectorsFormat =
        format.getKnnVectorsFormatForField(KNN_VECTOR.getLuceneFieldName(path, Optional.empty()));

    Assert.assertTrue(vectorsFormat instanceof Lucene99HnswVectorsFormat);
    VectorFieldSpecification.HnswOptions hnswOptions =
        getKnnFieldConfig(Lucene99HnswVectorsFormat.class, vectorsFormat);
    Assert.assertEquals(DEFAULT_MAX_CONN, hnswOptions.maxEdges());
    Assert.assertEquals(DEFAULT_BEAM_WIDTH, hnswOptions.numEdgeCandidates());
  }

  @Test
  public void getKnnVectorsFormatForField_withUnresolvedTypeField_producesDefaultFormat() {
    FieldPath path = FieldPath.parse("vector");
    VectorFieldSpecification specification = createVectorFieldDefinition(48, 566);

    LuceneCodec codec = new LuceneCodec(Map.of(path, specification));
    PerFieldKnnVectorsFormat format = (PerFieldKnnVectorsFormat) codec.knnVectorsFormat();

    assertThrows(AssertionError.class, () -> format.getKnnVectorsFormatForField(path.toString()));
  }

  private VectorFieldSpecification createVectorFieldDefinition(
      int maxEdges, int numEdgeCandidates) {
    return new VectorFieldSpecification(
        1024,
        VectorSimilarity.DOT_PRODUCT,
        VectorQuantization.NONE,
        new VectorIndexingAlgorithm.HnswIndexingAlgorithm(
            new VectorFieldSpecification.HnswOptions(maxEdges, numEdgeCandidates)));
  }

  private VectorFieldSpecification.HnswOptions getKnnFieldConfig(
      Class<? extends KnnVectorsFormat> knnFormatClass, KnnVectorsFormat format)
      throws NoSuchFieldException, IllegalAccessException {

    Field maxConnField = knnFormatClass.getDeclaredField("maxConn");
    maxConnField.setAccessible(true);
    int maxConn = (int) maxConnField.get(format);

    Field beamWidthField = knnFormatClass.getDeclaredField("beamWidth");
    beamWidthField.setAccessible(true);
    int beamWidth = (int) beamWidthField.get(format);

    return new VectorFieldSpecification.HnswOptions(maxConn, beamWidth);
  }

  @Test
  public void
      getKnnVectorsFormatForField_withFlatAlgo_producesFloat32AndByteFlatFormat_forKnnVector() {
    FieldPath path = FieldPath.parse("vector");
    VectorFieldSpecification specification = createFlatVectorFieldDefinition();

    LuceneCodec codec = new LuceneCodec(Map.of(path, specification));
    PerFieldKnnVectorsFormat format = (PerFieldKnnVectorsFormat) codec.knnVectorsFormat();
    KnnVectorsFormat vectorsFormat =
        format.getKnnVectorsFormatForField(KNN_VECTOR.getLuceneFieldName(path, Optional.empty()));

    Assert.assertTrue(vectorsFormat instanceof Float32AndByteFlatVectorsFormat);
  }

  @Test
  public void
      getKnnVectorsFormatForField_withFlatAlgo_producesFloat32AndByteFlatFormat_forKnnByte() {
    FieldPath path = FieldPath.parse("vector");
    VectorFieldSpecification specification = createFlatVectorFieldDefinition();

    LuceneCodec codec = new LuceneCodec(Map.of(path, specification));
    PerFieldKnnVectorsFormat format = (PerFieldKnnVectorsFormat) codec.knnVectorsFormat();
    KnnVectorsFormat vectorsFormat =
        format.getKnnVectorsFormatForField(KNN_BYTE.getLuceneFieldName(path, Optional.empty()));

    Assert.assertTrue(vectorsFormat instanceof Float32AndByteFlatVectorsFormat);
  }

  private VectorFieldSpecification createFlatVectorFieldDefinition() {
    return new VectorFieldSpecification(
        1024,
        VectorSimilarity.DOT_PRODUCT,
        VectorQuantization.NONE,
        new VectorIndexingAlgorithm.FlatIndexingAlgorithm());
  }
}
