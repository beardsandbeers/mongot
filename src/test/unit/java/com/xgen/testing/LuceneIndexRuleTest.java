package com.xgen.testing;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.util.BytesRef;
import org.junit.ClassRule;
import org.junit.Test;

public class LuceneIndexRuleTest {

  @ClassRule public static final LuceneIndexRule validator = new LuceneIndexRule();

  private static final LuceneIndexRule uninitializedValidator = new LuceneIndexRule();

  @Test
  public void validDocument() {
    Document valid = new Document();

    valid.add(new SortedDocValuesField("name", new BytesRef("one")));

    validator.add(valid);
  }

  @Test
  public void invalidDeclaration() {
    Document valid = new Document();

    valid.add(new SortedDocValuesField("name", new BytesRef("one")));

    Error e = assertThrows(AssertionError.class, () -> uninitializedValidator.add(valid));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("LuceneIndexRule not setup. Must be public and annotated with @ClassRule.");
  }

  @Test
  public void duplicateSortedDocValues() {
    Document invalid = new Document();

    invalid.add(new SortedDocValuesField("name", new BytesRef("one")));
    invalid.add(new SortedDocValuesField("name", new BytesRef("two")));

    AssertionError e = assertThrows(AssertionError.class, () -> validator.add(invalid));
    assertThat(e).hasMessageThat().startsWith("Invalid document:");
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .startsWith("DocValuesField \"name\" appears more than once");
  }

  @Test
  public void fieldTooLong() {
    Document invalid = new Document();

    invalid.add(
        new SortedDocValuesField("name", new BytesRef("bb".repeat(IndexWriter.MAX_TERM_LENGTH))));

    AssertionError e = assertThrows(AssertionError.class, () -> validator.add(invalid));
    assertThat(e).hasMessageThat().startsWith("Invalid document:");
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .startsWith("DocValuesField \"name\" is too large");
  }
}
