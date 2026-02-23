package com.xgen.mongot.index.analyzer;

import com.xgen.mongot.index.analyzer.definition.AnalyzerDefinition;
import com.xgen.mongot.index.analyzer.definition.CustomAnalyzerDefinition;
import com.xgen.mongot.index.analyzer.definition.OverriddenBaseAnalyzerDefinition;
import com.xgen.mongot.index.analyzer.definition.StockAnalyzerNames;
import com.xgen.mongot.index.analyzer.definition.StockNormalizerName;
import com.xgen.testing.mongot.index.analyzer.AnalyzerRegistryBuilder;
import com.xgen.testing.mongot.index.analyzer.AnalyzerTestUtil;
import com.xgen.testing.mongot.index.analyzer.custom.TokenFilterDefinitionBuilder;
import com.xgen.testing.mongot.index.analyzer.custom.TokenizerDefinitionBuilder;
import com.xgen.testing.mongot.index.analyzer.definition.CustomAnalyzerDefinitionBuilder;
import com.xgen.testing.mongot.index.analyzer.definition.OverriddenBaseAnalyzerDefinitionBuilder;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.StopAnalyzer;
import org.apache.lucene.analysis.gl.GalicianAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.junit.Assert;
import org.junit.Test;

public class AnalyzerRegistryTest {

  @Test
  public void testValidAnalyzer() throws Exception {
    OverriddenBaseAnalyzerDefinition definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("foo")
            .baseAnalyzerName(StockAnalyzerNames.LUCENE_STANDARD.getName())
            .build();
    AnalyzerRegistry registry = createRegistry(definition);
    registry.getAnalyzer("foo");
    Assert.assertEquals(List.of(definition), registry.getAnalyzerDefinitions());
  }

  @Test
  public void testValidCustomAnalyzer() throws Exception {
    CustomAnalyzerDefinition definition =
        CustomAnalyzerDefinitionBuilder.builder(
                "foo", TokenizerDefinitionBuilder.StandardTokenizer.builder().build())
            .build();
    AnalyzerRegistry registry = createRegistry(definition);
    registry.getAnalyzer("foo");
    Assert.assertEquals(List.of(definition), registry.getAnalyzerDefinitions());
  }

  @Test
  public void testValidCustomAnalyzerSameNameAsStockNormalizer() throws Exception {
    CustomAnalyzerDefinition definition =
        CustomAnalyzerDefinitionBuilder.builder(
                "lowercase", TokenizerDefinitionBuilder.KeywordTokenizer.build())
            .tokenFilter(TokenFilterDefinitionBuilder.LowercaseTokenFilter.builder().build())
            .build();
    AnalyzerRegistry registry = createRegistry(definition);
    registry.getAnalyzer("lowercase");
    registry.getNormalizer(StockNormalizerName.LOWERCASE);
    Assert.assertEquals(List.of(definition), registry.getAnalyzerDefinitions());
  }

  @Test
  public void testInvalidBaseAnalyzerRegister() {
    OverriddenBaseAnalyzerDefinition definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("foo")
            .baseAnalyzerName("invalid")
            .build();
    assertInvalid(definition);
  }

  @Test
  public void testStockAnalyzersAreCached() throws Exception {
    var registry = createRegistry(Collections.emptyList());
    var standard1 = registry.getAnalyzer("lucene.standard");
    var standard2 = registry.getAnalyzer("lucene.standard");
    Assert.assertSame(standard1, standard2);
    Assert.assertEquals(Collections.emptyList(), registry.getAnalyzerDefinitions());
  }

  @Test
  public void testStockNormalizersAreCached() throws Exception {
    var registry = createRegistry(Collections.emptyList());
    var none1 = registry.getNormalizer(StockNormalizerName.NONE);
    var none2 = registry.getNormalizer(StockNormalizerName.NONE);
    Assert.assertSame(none1, none2);
    Assert.assertEquals(Collections.emptyList(), registry.getAnalyzerDefinitions());
  }

  @Test
  public void testCustomAnalyzersAreCached() throws Exception {
    OverriddenBaseAnalyzerDefinition definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("foo")
            .baseAnalyzerName(StockAnalyzerNames.LUCENE_STANDARD.getName())
            .build();
    AnalyzerRegistry registry = createRegistry(definition);
    var analyzer1 = registry.getAnalyzer("foo");
    var analyzer2 = registry.getAnalyzer("foo");
    Assert.assertSame(analyzer1, analyzer2);
  }

  @Test
  public void testRegistriesDoNotShareCustomAnalyzers() throws Exception {
    OverriddenBaseAnalyzerDefinition definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("foo")
            .baseAnalyzerName(StockAnalyzerNames.LUCENE_STANDARD.getName())
            .build();
    var factory = AnalyzerRegistry.factory();
    var analyzer1 = factory.create(List.of(definition), true).getAnalyzer("foo");
    var analyzer2 = factory.create(List.of(definition), true).getAnalyzer("foo");
    Assert.assertNotSame(analyzer1, analyzer2);
  }

  @Test
  public void testRegistriesShareStockAnalyzers() throws Exception {
    var factory = AnalyzerRegistry.factory();
    var analyzer1 = factory.create(Collections.emptyList(), true).getAnalyzer("lucene.standard");
    var analyzer2 = factory.create(Collections.emptyList(), true).getAnalyzer("lucene.standard");
    Assert.assertSame(analyzer1, analyzer2);
  }

  @Test
  public void testRegistriesShareStockNormalizers() throws Exception {
    var factory = AnalyzerRegistry.factory();
    var normalizer1 =
        factory.create(Collections.emptyList(), true).getNormalizer(StockNormalizerName.NONE);
    var normalizer2 =
        factory.create(Collections.emptyList(), true).getNormalizer(StockNormalizerName.NONE);
    Assert.assertSame(normalizer1, normalizer2);
  }

  @Test
  public void testDuplicateAnalyzerNamesThrows() {
    var definition1 =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("foo")
            .baseAnalyzerName("lucene.standard")
            .build();
    var definition2 =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("foo")
            .baseAnalyzerName("lucene.simple")
            .build();
    Assert.assertThrows(
        InvalidAnalyzerDefinitionException.class,
        () -> createRegistry(List.of(definition1, definition2)));
  }

  @Test
  public void testAnalyzersWrappedWithByteSizeFilterAnalyzer() throws Exception {
    var registry = createRegistry(Collections.emptyList());
    Analyzer analyzer = registry.getAnalyzer(StockAnalyzerNames.LUCENE_KEYWORD.getName());
    Assert.assertEquals(TokenByteSizeFilterAnalyzer.class, analyzer.getClass());

    TokenByteSizeFilterAnalyzer filteredAnalyzer = (TokenByteSizeFilterAnalyzer) analyzer;
    Assert.assertEquals(
        KeywordAnalyzer.class, filteredAnalyzer.getWrappedAnalyzer("foo").getClass());

    // Ensure the analyzer wrapper is working (that is, it is filtering out tokens larger than
    // MAX_TOKEN_SIZE). This test is basically repeating
    // TokenByteSizeFilterTest::testLuceneMaxTermSizeTerm but with the wrapped analyzer rather than
    // the filter.

    // Term less than max.
    String oneLess = "a".repeat(IndexWriter.MAX_TERM_LENGTH - 1);
    AnalyzerTestUtil.testAnalyzerShouldProduceToken(analyzer, oneLess);

    // Term same as max.
    String same = oneLess + "a";
    AnalyzerTestUtil.testAnalyzerShouldProduceToken(analyzer, oneLess);

    // Term larger than max.
    String larger = same + "a";
    AnalyzerTestUtil.testShouldNotProduceToken(analyzer, larger);
  }

  @Test
  public void testValidOverriddenStopwordAnalyzer() throws Exception {
    Set<String> stopwords = Set.of("one", "two");
    OverriddenBaseAnalyzerDefinition definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("foo")
            .baseAnalyzerName(StockAnalyzerNames.LUCENE_STOP.getName())
            .stopwords(stopwords)
            .build();

    var registry = createRegistry(definition);

    Analyzer analyzer = registry.getAnalyzer("foo");
    TokenByteSizeFilterAnalyzer filterAnalyzer = (TokenByteSizeFilterAnalyzer) analyzer;
    StopAnalyzer stopAnalyzer = (StopAnalyzer) filterAnalyzer.getWrappedAnalyzer("foo");

    // Ensure that the registered analyzer contains exactly the same stopwords.
    Assert.assertEquals(stopwords.size(), stopAnalyzer.getStopwordSet().size());
    for (String stopword : stopwords) {
      Assert.assertTrue(stopAnalyzer.getStopwordSet().contains(stopword));
    }
  }

  @Test
  public void testValidOverriddenStemExclusionAnalyzer() throws Exception {
    Set<String> stopwords = Set.of("one", "two");
    Set<String> stemExclusions = Set.of("three", "four");
    OverriddenBaseAnalyzerDefinition definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("foo")
            .baseAnalyzerName(StockAnalyzerNames.LUCENE_GALICIAN.getName())
            .stopwords(stopwords)
            .excludeStems(stemExclusions)
            .build();

    var registry = createRegistry(definition);

    Analyzer analyzer = registry.getAnalyzer("foo");
    TokenByteSizeFilterAnalyzer filterAnalyzer = (TokenByteSizeFilterAnalyzer) analyzer;
    GalicianAnalyzer galicianAnalyzer = (GalicianAnalyzer) filterAnalyzer.getWrappedAnalyzer("foo");

    // Ensure that the registered analyzer contains exactly the same stopwords.
    Assert.assertEquals(stopwords.size(), galicianAnalyzer.getStopwordSet().size());
    for (String stopword : stopwords) {
      Assert.assertTrue(galicianAnalyzer.getStopwordSet().contains(stopword));
    }

    // Use reflection to grab the inner stemExclusionSet member.
    // TODO(CLOUDP-280897): doesn't seem like there's a better way to test this, but is this
    // something we want to rely on?
    Field stem = galicianAnalyzer.getClass().getDeclaredField("stemExclusionSet");
    stem.setAccessible(true);
    CharArraySet analyzerStemExclusion = (CharArraySet) stem.get(galicianAnalyzer);

    // Ensure that the registered analyzer contains exactly the same stem exclusion set.
    Assert.assertEquals(stemExclusions.size(), analyzerStemExclusion.size());
    for (String stemExclusion : stemExclusions) {
      Assert.assertTrue(analyzerStemExclusion.contains(stemExclusion));
    }
  }

  @Test
  public void testOverriddenStopwordAnalyzerRequiresStopwords() {
    OverriddenBaseAnalyzerDefinition definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("foo")
            .baseAnalyzerName(StockAnalyzerNames.LUCENE_STOP.getName())
            .build();
    assertInvalid(definition);
  }

  @Test
  public void testValidOverriddenAnalyzerWithStockNormalizerName()
      throws InvalidAnalyzerDefinitionException {
    OverriddenBaseAnalyzerDefinition definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name(StockNormalizerName.NONE.getNormalizerName())
            .baseAnalyzerName(StockAnalyzerNames.LUCENE_STANDARD.getName())
            .build();

    createRegistry(definition);
  }

  @Test
  public void testCannotRegisterStockAnalyzerName() {
    OverriddenBaseAnalyzerDefinition definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name(StockAnalyzerNames.LUCENE_STANDARD.getName())
            .baseAnalyzerName(StockAnalyzerNames.LUCENE_STANDARD.getName())
            .build();

    assertInvalid(definition);
  }

  @Test
  public void testGetWithMetadataHasAnalyzer() throws Exception {
    var definition =
        OverriddenBaseAnalyzerDefinitionBuilder.builder()
            .name("test")
            .baseAnalyzerName(StockAnalyzerNames.LUCENE_STANDARD.getName())
            .build();

    var registry = createRegistry(definition);

    var meta = registry.getAnalyzerMeta("test");
    Assert.assertSame(meta.getAnalyzer(), registry.getAnalyzer("test"));
  }

  @Test
  public void testGetWithMetadataHasNormalizer() throws Exception {
    var registry = createRegistry(Collections.emptyList());

    var meta = registry.getNormalizerMeta(StockNormalizerName.NONE);
    Assert.assertSame(meta.getAnalyzer(), registry.getNormalizer(StockNormalizerName.NONE));
  }

  @Test
  public void testGetAnalyzerForUndefinedAnalyzerThrows() {
    var registry = AnalyzerRegistryBuilder.empty();

    Assert.assertThrows(IllegalStateException.class, () -> registry.getAnalyzer("unknown"));
  }

  @Test
  public void testGetMetadataForUndefinedAnalyzerThrows() {
    var registry = AnalyzerRegistryBuilder.empty();

    Assert.assertThrows(IllegalStateException.class, () -> registry.getAnalyzerMeta("unknown"));
  }

  @Test
  public void testMetaReportsDerivedFromKeyword() throws Exception {
    var registry =
        createRegistry(
            List.of(
                OverriddenBaseAnalyzerDefinitionBuilder.builder()
                    .name("keyword-based")
                    .baseAnalyzerName(StockAnalyzerNames.LUCENE_KEYWORD.getName())
                    .build(),
                OverriddenBaseAnalyzerDefinitionBuilder.builder()
                    .name("non-keyword-based")
                    .baseAnalyzerName(StockAnalyzerNames.LUCENE_STANDARD.getName())
                    .build()));
    var keywordBasedMeta = registry.getAnalyzerMeta("keyword-based");
    Assert.assertTrue(keywordBasedMeta.derivedFromKeyword());
    var keywordMeta = registry.getAnalyzerMeta(StockAnalyzerNames.LUCENE_KEYWORD.getName());
    Assert.assertTrue(keywordMeta.derivedFromKeyword());

    var nonKeywordMeta = registry.getAnalyzerMeta("non-keyword-based");
    Assert.assertFalse(nonKeywordMeta.derivedFromKeyword());
    var otherStockAnalyzer = registry.getAnalyzerMeta(StockAnalyzerNames.LUCENE_STANDARD.getName());
    Assert.assertFalse(otherStockAnalyzer.derivedFromKeyword());
  }

  @Test
  public void testStockAnalyzerName() {
    Assert.assertTrue(AnalyzerRegistry.isStockAnalyzerName("lucene.standard"));
    Assert.assertTrue(AnalyzerRegistry.isStockAnalyzerName("lucene.keyword"));
    Assert.assertTrue(AnalyzerRegistry.isStockAnalyzerName("lucene.arabic"));
    Assert.assertTrue(AnalyzerRegistry.isStockAnalyzerName("lucene.cjk"));
    Assert.assertFalse(AnalyzerRegistry.isStockAnalyzerName("notStock"));
  }

  private AnalyzerRegistry createRegistry(AnalyzerDefinition definition)
      throws InvalidAnalyzerDefinitionException {
    return AnalyzerRegistry.factory().create(List.of(definition), true);
  }

  private AnalyzerRegistry createRegistry(List<AnalyzerDefinition> definition)
      throws InvalidAnalyzerDefinitionException {
    return AnalyzerRegistry.factory().create(definition, true);
  }

  private void assertInvalid(AnalyzerDefinition definition) {
    Assert.assertThrows(InvalidAnalyzerDefinitionException.class, () -> createRegistry(definition));
    Assert.assertThrows(
        InvalidAnalyzerDefinitionException.class,
        () -> AnalyzerRegistry.validateAnalyzerDefinition(definition));
  }
}
