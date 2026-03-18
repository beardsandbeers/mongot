package com.xgen.mongot.index.lucene;

import com.xgen.mongot.index.lucene.config.LuceneConfig;
import org.apache.lucene.search.IndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LuceneGlobalSettings {
  private static final Logger LOG = LoggerFactory.getLogger(LuceneGlobalSettings.class);

  // disableMaxClauseLimit flag is deprecated, use maxClauseLimit instead
  @SuppressWarnings("deprecation")
  public static void apply(LuceneConfig config) {
    if (config.disableMaxClauseLimit()) {
      IndexSearcher.setMaxClauseCount(Integer.MAX_VALUE);
    } else {
      // When maxClauseLimit is not set, the Lucene default (1024) is preserved.
      config.maxClauseLimit().ifPresent(IndexSearcher::setMaxClauseCount);
    }
    LOG.atInfo()
        .addKeyValue("maxClauseCount", IndexSearcher.getMaxClauseCount())
        .log("Lucene max clause count set");
  }
}
