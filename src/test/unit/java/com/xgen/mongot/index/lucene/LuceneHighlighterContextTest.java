package com.xgen.mongot.index.lucene;

import static com.google.common.truth.Truth.assertThat;

import com.xgen.mongot.index.definition.DocumentFieldDefinition;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.index.definition.SearchIndexDefinition;
import com.xgen.mongot.index.lucene.explain.timing.ExplainTimings;
import com.xgen.mongot.index.lucene.explain.tracing.Explain;
import com.xgen.mongot.index.lucene.searcher.LuceneIndexSearcher;
import com.xgen.mongot.index.lucene.searcher.QueryCacheProvider;
import com.xgen.mongot.index.query.InvalidQueryException;
import com.xgen.mongot.index.query.QueryOptimizationFlags;
import com.xgen.mongot.index.query.highlights.UnresolvedHighlight;
import com.xgen.mongot.index.version.IndexFormatVersion;
import com.xgen.testing.mongot.index.definition.AutocompleteFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.DocumentFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.FieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.SearchIndexDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.StringFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.path.string.UnresolvedStringPathBuilder;
import com.xgen.testing.mongot.index.query.QueryOptimizationFlagsBuilder;
import com.xgen.testing.mongot.index.query.highlights.UnresolvedHighlightBuilder;
import com.xgen.testing.mongot.index.query.operators.OperatorBuilder;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.junit.Assert;
import org.junit.Test;

public class LuceneHighlighterContextTest {

  @Test
  public void testGetHighlighterIfPresentForStringFieldAndExplain()
      throws IOException, InvalidQueryException {
    try (Directory directory = new ByteBuffersDirectory()) {
      try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
        Document doc = new Document();
        doc.add(new StringField("fieldA", "value", Field.Store.YES));
        writer.addDocument(doc);
        writer.commit();
      }
      DocumentFieldDefinition mappings =
          DocumentFieldDefinitionBuilder.builder()
              .dynamic(false)
              .field(
                  "fieldA",
                  FieldDefinitionBuilder.builder()
                      .string(StringFieldDefinitionBuilder.builder().store(true).build())
                      .build())
              .build();
      LuceneHighlighterContext context =
          new LuceneHighlighterContext(
              createIndexDefinition(mappings)
                  .createFieldDefinitionResolver(IndexFormatVersion.CURRENT),
              new StandardAnalyzer());
      IndexReader reader = DirectoryReader.open(directory);
      LuceneIndexSearcher searcher =
          LuceneIndexSearcher.create(
              reader,
              new QueryCacheProvider.DefaultQueryCacheProvider(),
              Optional.empty(),
              Optional.empty(),
              false,
              false,
              Optional.empty());
      UnresolvedHighlight highlight =
          UnresolvedHighlightBuilder.builder()
              .path(UnresolvedStringPathBuilder.wildcardPath("field*"))
              .build();

      try (var unused =
          Explain.setup(
              Optional.of(Explain.Verbosity.EXECUTION_STATS),
              Optional.of(IndexDefinition.Fields.NUM_PARTITIONS.getDefaultValue()))) {
        Optional<LuceneUnifiedHighlighter> highlighter =
            context.getHighlighterIfPresent(
                searcher,
                Optional.of(highlight),
                new TermQuery(new Term("$type:string/fieldA", "value")),
                OperatorBuilder.term().path("fieldA").query("value").build(),
                Optional.empty(),
                QueryOptimizationFlags.DEFAULT_OPTIONS);
        Assert.assertTrue(highlighter.isPresent());
        Assert.assertEquals(
            highlighter.get().getHighlight().resolvedLuceneFieldNames(),
            List.of("$type:string/fieldA"));
        Assert.assertEquals(
            Map.of("$type:string/fieldA", "$type:string/fieldA"),
            highlighter.get().getHighlight().storedLuceneFieldNameMap());

        var result = Explain.collect();
        assertThat(result.get().highlightStats()).isPresent();
        var highlightStats = result.get().highlightStats().get();

        assertThat(
                highlightStats
                    .stats()
                    .get()
                    .invocationCounts()
                    .get()
                    .get(ExplainTimings.Type.SETUP_HIGHLIGHT.getName()))
            .isEqualTo(1);
      }
    }
  }

  @Test
  public void testGetHighlighterIfPresentForAutocompleteField()
      throws IOException, InvalidQueryException {
    try (Directory directory = new ByteBuffersDirectory()) {
      try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
        Document doc = new Document();

        FieldType autocompleteFieldType = new FieldType();
        autocompleteFieldType.setIndexOptions(
            IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        autocompleteFieldType.setTokenized(true);
        autocompleteFieldType.setStored(true);
        autocompleteFieldType.freeze();
        doc.add(new Field("fieldA", "value", autocompleteFieldType));

        writer.addDocument(doc);
        writer.commit();
      }
      DocumentFieldDefinition mappings =
          DocumentFieldDefinitionBuilder.builder()
              .dynamic(false)
              .field(
                  "fieldA",
                  FieldDefinitionBuilder.builder()
                      .autocomplete(AutocompleteFieldDefinitionBuilder.builder().build())
                      .build())
              .build();
      LuceneHighlighterContext context =
          new LuceneHighlighterContext(
              createIndexDefinition(mappings)
                  .createFieldDefinitionResolver(IndexFormatVersion.CURRENT),
              new StandardAnalyzer());
      IndexReader reader = DirectoryReader.open(directory);
      LuceneIndexSearcher searcher =
          LuceneIndexSearcher.create(
              reader,
              new QueryCacheProvider.DefaultQueryCacheProvider(),
              Optional.empty(),
              Optional.empty(),
              false,
              false,
              Optional.empty());
      UnresolvedHighlight highlight =
          UnresolvedHighlightBuilder.builder()
              .path(UnresolvedStringPathBuilder.wildcardPath("field*"))
              .build();
      Optional<LuceneUnifiedHighlighter> highlighter =
          context.getHighlighterIfPresent(
              searcher,
              Optional.of(highlight),
              new TermQuery(new Term("$type:autocomplete/fieldA", "value")),
              OperatorBuilder.autocomplete().path("fieldA").query("value").build(),
              Optional.empty(),
              QueryOptimizationFlags.DEFAULT_OPTIONS);
      Assert.assertTrue(highlighter.isPresent());
      Assert.assertEquals(
          highlighter.get().getHighlight().resolvedLuceneFieldNames(),
          List.of("$type:autocomplete/fieldA"));
      Assert.assertEquals(
          Map.of("$type:autocomplete/fieldA", "$type:autocomplete/fieldA"),
          highlighter.get().getHighlight().storedLuceneFieldNameMap());
    }
  }

  @Test
  public void testGetHighlighterIfNotPresent() throws IOException, InvalidQueryException {
    try (Directory directory = new ByteBuffersDirectory()) {
      try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
        writer.commit();
      }
      SearchIndexDefinition indexDefinition =
          createIndexDefinition(DocumentFieldDefinitionBuilder.builder().dynamic(false).build());
      LuceneHighlighterContext context =
          new LuceneHighlighterContext(
              indexDefinition.createFieldDefinitionResolver(IndexFormatVersion.CURRENT),
              new StandardAnalyzer());
      IndexReader reader = DirectoryReader.open(directory);
      Optional<LuceneUnifiedHighlighter> highlighter =
          context.getHighlighterIfPresent(
              LuceneIndexSearcher.create(
                  reader,
                  new QueryCacheProvider.DefaultQueryCacheProvider(),
                  Optional.empty(),
                  Optional.empty(),
                  false,
                  false,
                  Optional.empty()),
              Optional.empty(),
              new TermQuery(new Term("$type:string/fieldA", "value")),
              OperatorBuilder.term().path("fieldA").query("value").build(),
              Optional.empty(),
              QueryOptimizationFlags.DEFAULT_OPTIONS);
      Assert.assertFalse(highlighter.isPresent());
    }
  }

  @Test
  public void testHighlighterTermsValidation() throws IOException {
    try (Directory directory = new ByteBuffersDirectory()) {
      try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
        Document doc = new Document();
        doc.add(new StringField("fieldA", "value", Field.Store.YES));
        writer.addDocument(doc);
        writer.commit();
      }
      DocumentFieldDefinition mappings =
          DocumentFieldDefinitionBuilder.builder()
              .dynamic(false)
              .field(
                  "fieldA",
                  FieldDefinitionBuilder.builder()
                      .string(StringFieldDefinitionBuilder.builder().store(true).build())
                      .build())
              .build();
      LuceneHighlighterContext context =
          new LuceneHighlighterContext(
              createIndexDefinition(mappings)
                  .createFieldDefinitionResolver(IndexFormatVersion.CURRENT),
              new StandardAnalyzer());
      IndexReader reader = DirectoryReader.open(directory);
      LuceneIndexSearcher searcher =
          LuceneIndexSearcher.create(
              reader,
              new QueryCacheProvider.DefaultQueryCacheProvider(),
              Optional.empty(),
              Optional.empty(),
              false,
              false,
              Optional.empty());
      UnresolvedHighlight highlight =
          UnresolvedHighlightBuilder.builder()
              .path(UnresolvedStringPathBuilder.wildcardPath("field*"))
              .build();
      Assert.assertThrows(
          AssertionError.class,
          () ->
              context.getHighlighterIfPresent(
                  searcher,
                  Optional.of(highlight),
                  new TermQuery(
                      new Term("$type:string/fieldA", new BytesRef(new byte[] {(byte) 0xA1}))),
                  OperatorBuilder.term().path("fieldA").query("value").build(),
                  Optional.empty(),
                  QueryOptimizationFlags.DEFAULT_OPTIONS));
    }
  }

  @Test
  public void testOmitSearchDocumentResults() throws Exception {
    try (Directory directory = new ByteBuffersDirectory()) {
      try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
        Document doc = new Document();
        doc.add(new StringField("fieldA", "value", Field.Store.YES));
        writer.addDocument(doc);
        writer.commit();
      }
      DocumentFieldDefinition mappings =
          DocumentFieldDefinitionBuilder.builder()
              .dynamic(false)
              .field(
                  "fieldA",
                  FieldDefinitionBuilder.builder()
                      .string(StringFieldDefinitionBuilder.builder().store(true).build())
                      .build())
              .build();
      LuceneHighlighterContext context =
          new LuceneHighlighterContext(
              createIndexDefinition(mappings)
                  .createFieldDefinitionResolver(IndexFormatVersion.CURRENT),
              new StandardAnalyzer());
      IndexReader reader = DirectoryReader.open(directory);
      LuceneIndexSearcher searcher =
          LuceneIndexSearcher.create(
              reader,
              new QueryCacheProvider.DefaultQueryCacheProvider(),
              Optional.empty(),
              Optional.empty(),
              false,
              false,
              Optional.empty());
      UnresolvedHighlight highlight =
          UnresolvedHighlightBuilder.builder()
              .path(UnresolvedStringPathBuilder.wildcardPath("field*"))
              .build();
      assertThat(
          context
              .getHighlighterIfPresent(
                  searcher,
                  Optional.of(highlight),
                  new TermQuery(
                      new Term("$type:string/fieldA", new BytesRef(new byte[] {(byte) 0xA1}))),
                  OperatorBuilder.term().path("fieldA").query("value").build(),
                  Optional.empty(),
                  QueryOptimizationFlagsBuilder.builder().omitSearchDocumentResults(true).build())
              .isEmpty());
    }
  }

  private SearchIndexDefinition createIndexDefinition(DocumentFieldDefinition mappings) {
    return SearchIndexDefinitionBuilder.builder().defaultMetadata().mappings(mappings).build();
  }
}
