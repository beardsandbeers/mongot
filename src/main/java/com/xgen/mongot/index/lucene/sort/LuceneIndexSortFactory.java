package com.xgen.mongot.index.lucene.sort;

import com.google.common.collect.ImmutableSet;
import com.xgen.mongot.index.definition.FieldDefinition;
import com.xgen.mongot.index.definition.NumericFieldDefinition;
import com.xgen.mongot.index.definition.SearchFieldDefinitionResolver;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.lucene.query.sort.LuceneSortFactory;
import com.xgen.mongot.index.lucene.query.sort.MqlDateSort;
import com.xgen.mongot.index.lucene.query.sort.MqlLongSort;
import com.xgen.mongot.index.query.sort.MongotSortField;
import com.xgen.mongot.index.query.sort.NullEmptySortPosition;
import com.xgen.mongot.index.query.sort.Sort;
import com.xgen.mongot.index.query.sort.UserFieldSortOptions;
import com.xgen.mongot.util.Check;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.search.SortField;

/**
 * Factory for creating Lucene {@link org.apache.lucene.search.Sort} objects for index sorting.
 * Assumes sort fields have been pre-validated in
 * {@link com.xgen.mongot.index.definition.IndexSortValidator}
 */
public class LuceneIndexSortFactory {
  private final SearchFieldDefinitionResolver fieldDefinitionResolver;

  public LuceneIndexSortFactory(SearchFieldDefinitionResolver fieldDefinitionResolver) {
    this.fieldDefinitionResolver = fieldDefinitionResolver;
  }

  /**
   * Create a Lucene Sort from a validated Sort spec.
   *
   * @param sort the sprt specification
   * @return Lucene Sort object
   */
  public org.apache.lucene.search.Sort createIndexSort(Sort sort) {
    List<SortField> sortFields = new ArrayList<>();

    for (MongotSortField mongotSortField : sort.getSortFields()) {
      SortField luceneSortField = createSortFieldFromMongotField(mongotSortField);
      sortFields.add(luceneSortField);
    }

    return new org.apache.lucene.search.Sort(sortFields.toArray(new SortField[0]));
  }

  private SortField createSortFieldFromMongotField(MongotSortField mongotSortField) {
    FieldDefinition fieldDefinition = Check.isPresent(
        this.fieldDefinitionResolver.getFieldDefinition(
            mongotSortField.field(),
            Optional.empty()), "fieldDefinition");

    FieldName.TypeField typeField = determineTypeField(fieldDefinition, mongotSortField);

    Optional<SortField> sortField =
        LuceneSortFactory.createOptimizedSortField(
            mongotSortField,
            ImmutableSet.of(typeField),
            Optional.empty(),
            Optional.empty(),
            this.fieldDefinitionResolver.getIndexCapabilities());

    if (sortField.isEmpty()) {
      throw new IllegalStateException("Failed to create sort field " + mongotSortField.field());
    }

    // Note that this cast is safe as index definition has already been validated in
    // IndexSortValidator.
    UserFieldSortOptions options =
        Check.instanceOf(mongotSortField.options(), UserFieldSortOptions.class);

    // Set the missing value for the sort field.
    // Note only MqlDoubleSort and MqlLongSort are not set missing values and we can't set it in
    // their constructors as it will break the nulls query sort pruning.
    switch (sortField.get()) {
      case MqlDateSort sort ->
          sort.setMissingValue(getLuceneMissingValue(options.nullEmptySortPosition()));
      case MqlLongSort sort ->
          sort.setMissingValue(getLuceneMissingValue(options.nullEmptySortPosition()));
      default -> {
        // No-op
      }
    }
    Check.checkState(sortField.get().getMissingValue() != null,
        "Can't find missing value for sort field");
    return sortField.get();
  }

  /**
   * Determines the TypeField for a given field definition and sort field.
   *
   * <p>Note: The hierarchy order of checks here doesn't represent priority - it takes advantage
   * of the fact that in Sorted Index Private Preview, only one type definition is allowed
   * per field. In public preview, this function will be changed to support multi-type fields.
   */
  private FieldName.TypeField determineTypeField(
      FieldDefinition fieldDefinition, MongotSortField mongotSortField) {
    // Boolean
    if (fieldDefinition.booleanFieldDefinition().isPresent()
        && this.fieldDefinitionResolver
        .getIndexCapabilities()
        .supportsObjectIdAndBooleanDocValues()) {
      return FieldName.TypeField.BOOLEAN;
    }

    // Date
    if (fieldDefinition.dateFieldDefinition().isPresent()) {
      return FieldName.TypeField.DATE_V2;
    }

    // Token
    if (fieldDefinition.tokenFieldDefinition().isPresent()) {
      return FieldName.TypeField.TOKEN;
    }

    // UUID
    if (fieldDefinition.uuidFieldDefinition().isPresent()) {
      return FieldName.TypeField.UUID;
    }

    // ObjectID
    if (fieldDefinition.objectIdFieldDefinition().isPresent()) {
      return FieldName.TypeField.OBJECT_ID;
    }

    // Number
    if (fieldDefinition.numberFieldDefinition().isPresent()) {
      return determineNumberTypeField(fieldDefinition.numberFieldDefinition().get());
    }
    throw new IllegalArgumentException(
        "No supported sortable type found for field: " + mongotSortField.field());
  }

  private FieldName.TypeField determineNumberTypeField(
      NumericFieldDefinition numberFieldDefinition) {
    FieldName.TypeField typeField = switch (numberFieldDefinition.options().representation()) {
      case INT64 -> FieldName.TypeField.NUMBER_INT64_V2;
      case DOUBLE -> FieldName.TypeField.NUMBER_DOUBLE_V2;
    };
    return typeField;
  }

  /**
   * Map nulls to either MIN_VALUE or MAX_VALUE depending on `position`.
   *
   * <p>Note: This doesn't order correctly between null values and MIN_VALUE/MAX_VALUE documents.
   * This is not a correctness issue since query sort handles nulls correctly in mqlLongCompare
   * function. This will impact that query sort will not fully benefit from index sort since
   * the missing value is different from the query sort field, however the sort pruning should be
   * more effective given the documents haven't pre-sorted on the same query sort fields.
   *
   * <p>This limitation will be lifted in public preview in CLOUDP-360859
   * where we will provide an additional meta field representing the doc's nullness on this field
   * and sort both fields together with respect to noData option.
   */
  private static long getLuceneMissingValue(NullEmptySortPosition position) {
    return switch (position) {
      case LOWEST -> Long.MIN_VALUE;
      case HIGHEST -> Long.MAX_VALUE;
    };
  }
}
