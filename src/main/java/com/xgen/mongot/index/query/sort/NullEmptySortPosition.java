package com.xgen.mongot.index.query.sort;

import com.xgen.mongot.util.BsonUtils;
import org.bson.BsonValue;

/**
 * This enum represents how nulls/missing/empty array ([]) values should be treated when sorting,
 * and provides the corresponding BSON sort value that is returned via $searchSortValues.
 */
public enum NullEmptySortPosition {
  LOWEST(10, 10, BsonUtils.MIN_KEY),
  // TODO(CLOUDP-280897): Support NullEmptySortPosition.DEFAULT once we start indexing `[]`.
  // DEFAULT(15, 20, ...),
  HIGHEST(240, 240, BsonUtils.MAX_KEY);

  public final int nullMissingPriority;
  public final int emptyArrayPriority;

  // This suppression is required for compilation - note that the constants BsonUtils.MIN_KEY and
  // BsonUtils.MAX_KEY are actually immutable.
  @SuppressWarnings("Immutable")
  private final BsonValue sortValue;

  NullEmptySortPosition(int nullMissingPriority, int emptyArrayPriority, BsonValue sortValue) {
    this.nullMissingPriority = nullMissingPriority;
    this.emptyArrayPriority = emptyArrayPriority;
    this.sortValue = sortValue;
  }

  /**
   * Returns the BSON sort value used for null, missing, and empty array ([]) field values.
   *
   * <p>This method implements a workaround for custom nulls sorting in sharded deployments. It maps
   * null/missing/[] values to either BsonMinKey or BsonMaxKey, depending on the specified
   * NullEmptySortPosition.
   *
   * <p>Note: This implementation is temporary until mongos supports custom reordering of BsonNulls
   * (see SERVER-93244).
   *
   * <p>TODO(CLOUDP-266197): Once support is added, this method will be updated to return {@link
   * org.bson.BsonNull#VALUE} for the DEFAULT position.
   *
   * @return {@link BsonUtils#MIN_KEY} if nulls should be treated as lowest, {@link
   *     BsonUtils#MAX_KEY} if highest
   */
  public BsonValue getNullMissingSortValue() {
    return this.sortValue;
  }

  /**
   * Returns the Lucene missing value for the $nullness sort field. Present documents have
   * nullness=0. For LOWEST: missing value < 0 so nulls sort before present docs. For HIGHEST:
   * missing value > 0 so nulls sort after present docs.
   */
  public long getNullnessMissingValue() {
    return switch (this) {
      case LOWEST -> Long.MIN_VALUE;
      case HIGHEST -> Long.MAX_VALUE;
    };
  }
}
