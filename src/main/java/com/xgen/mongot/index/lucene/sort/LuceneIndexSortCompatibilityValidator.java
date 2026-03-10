package com.xgen.mongot.index.lucene.sort;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.logging.DefaultKeyValueLogger;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.store.Directory;

/**
 * Validates that an existing on-disk Lucene index sort is compatible with a new index sort
 * configuration. This catches cases where the new sort includes $meta/nullness fields (for
 * INT64/Date) but the existing index does not, requiring a reindex.
 */
public final class LuceneIndexSortCompatibilityValidator {

  private static final DefaultKeyValueLogger logger =
      DefaultKeyValueLogger.getLogger(LuceneIndexSortCompatibilityValidator.class, new HashMap<>());

  private LuceneIndexSortCompatibilityValidator() {}

  /**
   * Validates that an existing on-disk index sort is compatible with the new index sort. If the new
   * sort contains $meta/nullness fields (for INT64/Date) but the existing index does not, the index
   * must be rebuilt — throws IllegalStateException with a clear message.
   */
  public static void validate(Directory directory, Sort newIndexSort) throws IOException {
    if (!hasNullnessSortField(newIndexSort)) {
      return;
    }

    try {
      SegmentInfos segmentInfos = SegmentInfos.readLatestCommit(directory);
      if (segmentInfos.asList().isEmpty()) {
        logger.atInfo().log(
            "No existing Lucene segments found, skipping index sort compatibility validation");
        return;
      }

      @Var boolean hasSegmentWithIndexSort = false;
      for (var segmentCommitInfo : segmentInfos.asList()) {
        Sort existingSort = segmentCommitInfo.info.getIndexSort();
        if (existingSort == null) {
          logger
              .atWarn()
              .addKeyValue("segment", segmentCommitInfo.info.name)
              .log(
                  "Existing segment is missing index sort; "
                      + "skipping nullness compatibility check for this segment");
          continue;
        }

        hasSegmentWithIndexSort = true;
        if (!hasNullnessSortField(existingSort)) {
          logger
              .atWarn()
              .addKeyValue("segment", segmentCommitInfo.info.name)
              .addKeyValue("existingSortFields", Arrays.toString(existingSort.getSort()))
              .addKeyValue("newSortFields", Arrays.toString(newIndexSort.getSort()))
              .log("Existing index sort is incompatible with new nullness sort format");
          throw new IllegalStateException(
              "Existing index sort is missing $meta/nullness fields required for Long/Date "
                  + "sort fields. The index must be rebuilt to apply the new sort format.");
        }
      }

      if (!hasSegmentWithIndexSort) {
        logger
            .atWarn()
            .log(
                "All existing segments are missing index sort metadata; "
                    + "skipping nullness compatibility validation");
      }
    } catch (IndexNotFoundException e) {
      logger.atInfo().log(
          "No existing Lucene index found on disk, skipping index sort compatibility validation");
    }
  }

  private static boolean hasNullnessSortField(Sort sort) {
    String nullnessPrefix = FieldName.MetaField.NULLNESS.getLuceneFieldName();
    return Arrays.stream(sort.getSort())
        .map(SortField::getField)
        .anyMatch(field -> field != null && field.startsWith(nullnessPrefix));
  }
}
