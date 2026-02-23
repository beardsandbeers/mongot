package com.xgen.testing;

import static com.xgen.testing.LuceneTestUtils.document;
import static org.junit.Assert.assertThrows;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.junit.Test;

public class LuceneTestUtilsTest {

  @Test
  public void unorderedDocsAreEqual() {
    Document x = document(new NumericDocValuesField("x", 1), new NumericDocValuesField("y", 2));
    Document y = document(new NumericDocValuesField("y", 2), new NumericDocValuesField("x", 1));

    LuceneTestUtils.assertFieldsEquals(x, y);
  }

  @Test
  public void differentTypesAreUnequal() {
    var x = document(new NumericDocValuesField("x", 2), new NumericDocValuesField("x", 2));
    var y = document(new SortedNumericDocValuesField("x", 2), new NumericDocValuesField("x", 2));

    assertThrows(AssertionError.class, () -> LuceneTestUtils.assertFieldsEquals(x, y));
  }
}
