package com.xgen.mongot.index.lucene;

import com.xgen.mongot.index.lucene.config.LuceneConfig;
import com.xgen.testing.mongot.index.lucene.LuceneConfigBuilder;
import org.apache.lucene.search.IndexSearcher;
import org.junit.Assert;
import org.junit.Test;

public class LuceneGlobalSettingsTest {
  @Test
  public void testSettingsAreApplied() {
    LuceneConfig luceneConfig =
        LuceneConfigBuilder.builder().tempDataPath().disableMaxClauses(true).build();

    int saveValue = IndexSearcher.getMaxClauseCount();
    try {
      LuceneGlobalSettings.apply(luceneConfig);
      Assert.assertEquals(IndexSearcher.getMaxClauseCount(), Integer.MAX_VALUE);
    } finally {
      IndexSearcher.setMaxClauseCount(saveValue);
      Assert.assertEquals(1024, IndexSearcher.getMaxClauseCount());
    }
  }

  @Test
  public void testDefaultIsToNotApply() {
    LuceneConfig luceneConfig = LuceneConfigBuilder.builder().tempDataPath().build();

    LuceneGlobalSettings.apply(luceneConfig);
    Assert.assertEquals(1024, IndexSearcher.getMaxClauseCount());
  }
}
