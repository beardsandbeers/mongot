package com.xgen.mongot.standalone.ingestion;

import com.xgen.mongot.index.DocumentEvent;
import com.xgen.mongot.index.DocumentMetadata;
import com.xgen.mongot.index.IndexWriter;
import com.xgen.mongot.index.lucene.util.LuceneDocumentIdEncoder;
import com.xgen.mongot.standalone.store.StandaloneStore.MutationRecord;
import com.xgen.mongot.util.regex.RegexBudget;
import java.util.List;
import java.util.Optional;
import org.bson.RawBsonDocument;

/** Generation-confined bridge to mongot's BSON mapping, analyzer and document-block pipeline. */
final class StandaloneDocumentIndexer {
  private final IndexWriter writer;

  StandaloneDocumentIndexer(IndexWriter writer) {
    this.writer = writer;
  }

  List<DocumentEvent> prepare(List<MutationRecord> mutations) throws Exception {
    return this.writer.prepareBatch(
        mutations.stream().map(StandaloneDocumentIndexer::event).toList());
  }

  void apply(DocumentEvent event) throws Exception {
    try (var ignored = RegexBudget.openScope()) {
      this.writer.updateIndex(event);
    }
    var limits = this.writer.exceededLimits();
    if (limits.isPresent()) {
      throw limits.get();
    }
  }

  void snapshotDocument(byte[] bson) {
    try {
      var event = replacement(bson);
      for (var prepared : this.writer.prepareBatch(List.of(event))) {
        apply(prepared);
      }
    } catch (Exception e) {
      throw new java.util.concurrent.CompletionException(e);
    }
  }

  private static DocumentEvent event(MutationRecord mutation) {
    return mutation.delete()
        ? DocumentEvent.createDelete(LuceneDocumentIdEncoder.decodeDocumentId(mutation.encodedId()))
        : replacement(mutation.bson());
  }

  private static DocumentEvent replacement(byte[] bson) {
    var document = new RawBsonDocument(bson);
    return DocumentEvent.createUpdate(
        DocumentMetadata.fromOriginalDocument(Optional.of(document)), document);
  }
}
