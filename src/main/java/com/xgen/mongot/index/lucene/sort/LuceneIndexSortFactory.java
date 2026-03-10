package com.xgen.mongot.index.lucene.sort;

import com.google.common.collect.ImmutableSet;
import com.xgen.mongot.index.definition.FieldDefinition;
import com.xgen.mongot.index.definition.NumericFieldDefinition;
import com.xgen.mongot.index.definition.SearchFieldDefinitionResolver;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.lucene.query.sort.LuceneSortFactory;
import com.xgen.mongot.index.query.sort.MongotSortField;
import com.xgen.mongot.index.query.sort.Sort;
import com.xgen.mongot.util.Check;
import com.xgen.mongot.util.FieldPath;
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
      sortFields.addAll(createSortFieldsFromMongotField(mongotSortField));
    }

    return new org.apache.lucene.search.Sort(sortFields.toArray(new SortField[0]));
  }

  private List<SortField> createSortFieldsFromMongotField(MongotSortField mongotSortField) {
    FieldDefinition fieldDefinition = Check.isPresent(
        this.fieldDefinitionResolver.getFieldDefinition(
            mongotSortField.field(), Optional.empty()), "fieldDefinition");

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

    boolean needsNullness =
        typeField == FieldName.TypeField.NUMBER_INT64_V2
            || typeField == FieldName.TypeField.DATE_V2;

    List<SortField> result = new ArrayList<>();
    if (needsNullness) {
      FieldPath nullnessPath =
          FieldPath.newRoot(FieldName.getNullnessFieldName(mongotSortField.field()));
      MongotSortField nullnessSortField =
          new MongotSortField(nullnessPath, mongotSortField.options());
      result.add(LuceneSortFactory.createNullnessSortField(nullnessSortField));
    }
    result.add(sortField.get());
    return result;
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
}
