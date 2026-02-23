package com.xgen.mongot.index.lucene.codec;

import com.xgen.mongot.index.lucene.util.FieldTypeBuilder;
import java.io.IOException;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.index.BaseStoredFieldsFormatTestCase;
import org.junit.Test;

public class LuceneStoredFieldsFormatTest extends BaseStoredFieldsFormatTestCase {

  @Override
  protected Codec getCodec() {
    return new LuceneCodec();
  }

  @Test
  public void testOriginalLucene92CodecIsAbleToReadIndexCreatedByOurCustomCodec()
      throws IOException {

    var directory = newDirectory();

    /*
     * Use an IndexWriter with the customized LuceneCodec to insert a document.
     */
    var customWriter =
        new IndexWriter(
            directory,
            new IndexWriterConfig(new MockAnalyzer(random()))
                .setCodec(new LuceneCodec())
                .setCommitOnClose(true));
    var insertedDocument = new Document();

    var fieldType = new FieldTypeBuilder().stored(true).build();
    insertedDocument.add(newField("a", "1", fieldType));
    insertedDocument.add(newField("b", "2", fieldType));
    insertedDocument.add(newField("c", "3", fieldType));
    customWriter.addDocument(insertedDocument);
    customWriter.close();

    /*
     * On directory open, Lucene will read metadata encoded in the segment to understand which
     * codec should be used to read it. It will then try to look up this codec by name using
     * Java SPI. Here we verify that stored fields written by our custom codec can be read by
     * the original Lucene92 codec.
     */
    var defaultReader = DirectoryReader.open(directory);
    var iterator = defaultReader.storedFields().document(0).getFields().iterator();

    assertTrue(iterator.hasNext());
    var fieldA = (Field) iterator.next();
    assertEquals("a", fieldA.name());
    assertEquals("1", fieldA.stringValue());

    assertTrue(iterator.hasNext());
    var fieldB = (Field) iterator.next();
    assertEquals("b", fieldB.name());
    assertEquals("2", fieldB.stringValue());

    assertTrue(iterator.hasNext());
    var fieldC = (Field) iterator.next();
    assertEquals("c", fieldC.name());
    assertEquals("3", fieldC.stringValue());

    assertFalse(iterator.hasNext());
    defaultReader.close();
    directory.close();
  }
}
