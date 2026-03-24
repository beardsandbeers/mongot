package com.xgen.mongot.embedding.utils;

import static com.xgen.mongot.embedding.config.MaterializedViewCollectionMetadata.MaterializedViewSchemaMetadata;
import static com.xgen.mongot.embedding.utils.AutoEmbeddingDocumentUtils.HASH_FIELD_SUFFIX;

import com.xgen.mongot.index.definition.VectorAutoEmbedFieldDefinition;
import com.xgen.mongot.index.definition.VectorDataFieldDefinition;
import com.xgen.mongot.index.definition.VectorIndexDefinition;
import com.xgen.mongot.index.definition.VectorIndexFieldDefinition;
import com.xgen.mongot.index.definition.VectorIndexFieldMapping;
import com.xgen.mongot.index.definition.VectorIndexFilterFieldDefinition;
import com.xgen.mongot.index.definition.VectorIndexVectorFieldDefinition;
import com.xgen.mongot.util.FieldPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class AutoEmbeddingIndexDefinitionUtils {

  private static final String HASH_FIELD_PREFIX = "_autoEmbed._hash";

  /**
   * Converts original raw VectorIndexDefinition in source collection to normalized
   * VectorIndexDefinition with following changes:
   *
   * <p>1. Converts VectorIndexFieldDefinition fields from AUTO_EMBED TO VECTOR type by input
   * materializedView schemaFieldsMapping
   *
   * <p>For example: {'title': {'path': 'title', 'type': 'autoEmbed', 'model': 'voyage-4',
   * 'modality': 'text'}, 'plot': {'path': 'plot', 'type': 'filter'}}
   *
   * <p>will be converted to:
   *
   * <p>{'_autoEmbed.title': {'path': '_autoEmbed.title', 'type': 'vector', 'numDimensions': 1024,
   * 'similarity': 'dotProduct'}, 'plot': {'path': 'plot', 'type': 'filter'}}
   *
   * <p>2. Replaces the collection UUID with the materialized view collection UUID, updates database
   * name.
   */
  public static VectorIndexDefinition getDerivedVectorIndexDefinition(
      VectorIndexDefinition rawDefinition,
      String databaseName,
      UUID materializedViewCollectionUuid,
      MaterializedViewSchemaMetadata schemaMetadata) {
    return new VectorIndexDefinition(
        rawDefinition.getIndexId(),
        rawDefinition.getName(),
        databaseName,
        rawDefinition.getIndexId().toHexString(),
        materializedViewCollectionUuid,
        Optional.empty(),
        rawDefinition.getNumPartitions(),
        getDerivedVectorIndexFields(rawDefinition.getFields(), schemaMetadata),
        rawDefinition.getParsedIndexFeatureVersion(),
        rawDefinition.getDefinitionVersion(),
        rawDefinition.getDefinitionVersionCreatedAt(),
        Optional.empty(), // TODO(https://jira.mongodb.org/browse/CLOUDP-363302)
        rawDefinition.getNestedRoot(),
        rawDefinition.getIndexIdAtCreationTime(),
        rawDefinition.getAutoEmbeddingDefinitionVersion(),
        rawDefinition.getMaterializedViewNameFormatVersion());
  }

  /**
   * Returns the hash field path for the given field path by materialized view schema version
   *
   * <p>For version 0, converts field path by appending _hash to leaf
   *
   * <p>For version 1 and above, prepends '_autoEmbed._hash.' to the field path
   *
   * @param fieldPath the current field path from source collection
   * @return the hash field path corresponding to the given field path
   */
  public static FieldPath getHashFieldPath(FieldPath fieldPath, long matViewSchemaVersion) {
    // TODO(CLOUDP-363914): build hash field from MV schema metadata, not by version.
    if (matViewSchemaVersion == 0) {
      return fieldPath
          .getParent()
          .map(path -> path.newChild(fieldPath.getLeaf() + HASH_FIELD_SUFFIX))
          .orElse(FieldPath.newRoot(fieldPath.getLeaf() + HASH_FIELD_SUFFIX));
    }
    return FieldPath.parse(HASH_FIELD_PREFIX + FieldPath.DELIMITER + fieldPath.toString());
  }

  /**
   * Creates a new VectorIndexFieldMapping from original VectorIndexFieldMapping by converting
   * AUTO_EMBED VectorIndexFieldDefinition and adding hash VectorIndexFieldDefinition if
   * VectorIndexFieldDefinition is set.
   *
   * <p>For example: {'title': {'path': 'title', 'type': 'autoEmbed', 'model': 'voyage-4',
   * 'modality': 'text'}, 'plot': {'path': 'plot', 'type': 'filter'}}
   *
   * <p>will be converted to:
   *
   * <p>{'_autoEmbed.title': {'path': '_autoEmbed.title', 'type': 'autoEmb', 'numDimensions': 1024,
   * 'similarity': 'dotProduct'}, '_autoEmbed._hash.title': {'path': '_autoEmbed._hash.title',
   * 'type': 'filter'}, 'plot': {'path': 'plot', 'type': 'filter'}}
   */
  public static VectorIndexFieldMapping getMatViewIndexFields(
      VectorIndexFieldMapping rawFieldMapping, MaterializedViewSchemaMetadata schemaMetadata) {
    List<VectorIndexFieldDefinition> updatedFieldDefinitions = new ArrayList<>();
    rawFieldMapping.fieldMap().values().stream()
        .forEach(
            field -> {
              if (field.getType() == VectorIndexFieldDefinition.Type.AUTO_EMBED) {
                var specification = field.asVectorAutoEmbedField().specification();
                updatedFieldDefinitions.add(
                    new VectorAutoEmbedFieldDefinition(
                        specification.modelName(),
                        specification.modality(),
                        getMatViewFieldPath(
                            field.getPath(), schemaMetadata.autoEmbeddingFieldsMapping()),
                        specification.similarity(),
                        specification.quantization()));
                // Use Filter field definition for internal Hash Field. Derived Definition should
                // exclude hash fields, this is only for auto-embedding resync process.
                updatedFieldDefinitions.add(
                    new VectorIndexFilterFieldDefinition(
                        getHashFieldPath(
                            field.getPath(), schemaMetadata.materializedViewSchemaVersion())));

              } else {
                updatedFieldDefinitions.add(field);
              }
            });
    return VectorIndexFieldMapping.create(updatedFieldDefinitions, rawFieldMapping.nestedRoot());
  }

  // Converts source auto-embedding field path to materialized view field path if there is schema
  // fields mapping available, returns original field path otherwise
  static FieldPath getMatViewFieldPath(
      FieldPath sourceFieldPath, Map<FieldPath, FieldPath> schemaFieldsMapping) {
    if (schemaFieldsMapping.containsKey(sourceFieldPath)) {
      return schemaFieldsMapping.get(sourceFieldPath);
    }
    return sourceFieldPath;
  }

  private static List<VectorIndexFieldDefinition> getDerivedVectorIndexFields(
      List<VectorIndexFieldDefinition> rawFields, MaterializedViewSchemaMetadata schemaMetadata) {
    return rawFields.stream()
        .map(
            field -> {
              if (field.getType() == VectorIndexFieldDefinition.Type.AUTO_EMBED) {
                VectorIndexVectorFieldDefinition autoEmbedField = field.asVectorField();
                return new VectorDataFieldDefinition(
                    getMatViewFieldPath(
                        field.getPath(), schemaMetadata.autoEmbeddingFieldsMapping()),
                    autoEmbedField.specification());
              }
              return field;
            })
        .toList();
  }
}
