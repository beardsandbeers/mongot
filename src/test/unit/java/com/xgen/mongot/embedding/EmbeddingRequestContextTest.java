package com.xgen.mongot.embedding;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.xgen.mongot.index.definition.quantization.VectorAutoEmbedQuantization;
import org.junit.Test;

public class EmbeddingRequestContextTest {

  @Test
  public void constructor_acceptsVoyageOutputFields() {
    EmbeddingRequestContext ctx =
        new EmbeddingRequestContext("db", "idx", "coll", 512, VectorAutoEmbedQuantization.SCALAR);
    assertThat(ctx.database()).isEqualTo("db");
    assertThat(ctx.indexName()).isEqualTo("idx");
    assertThat(ctx.collectionName()).isEqualTo("coll");
    assertThat(ctx.outputDimension()).isEqualTo(512);
    assertThat(ctx.autoEmbedQuantization()).isEqualTo(VectorAutoEmbedQuantization.SCALAR);
  }

  @Test
  public void constructor_rejectsNonPositiveDimension() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EmbeddingRequestContext("db", "idx", "coll", 0, VectorAutoEmbedQuantization.FLOAT));
  }
}
