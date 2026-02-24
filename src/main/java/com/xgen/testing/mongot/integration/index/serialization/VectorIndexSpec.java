package com.xgen.testing.mongot.integration.index.serialization;

import com.google.common.collect.ImmutableList;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.index.definition.StoredSourceDefinition;
import com.xgen.mongot.index.definition.VectorIndexFieldDefinition;
import com.xgen.mongot.index.version.IndexFormatVersion;
import com.xgen.mongot.util.FieldPath;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import com.xgen.mongot.util.bson.parser.FieldPathField;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.bson.BsonDocument;

public class VectorIndexSpec extends IndexSpec {

  public static final VectorIndexSpec EMPTY =
      new VectorIndexSpec(
          ImmutableList.of(),
          IndexDefinition.Fields.NUM_PARTITIONS.getDefaultValue(),
          Fields.RUN_FOR.getDefaultValue(),
          Optional.of(StoredSourceDefinition.defaultValue()),
          Optional.empty());

  static class Fields {
    static final Field.Required<List<VectorIndexFieldDefinition>> FIELDS =
        Field.builder("fields")
            .classField(VectorIndexFieldDefinition::fromBson)
            .disallowUnknownFields()
            .asList()
            .mustNotBeEmpty()
            .required();

    static final Field.WithDefault<VectorIndexRunForSpec> RUN_FOR =
        Field.builder("runFor")
            .classField(VectorIndexRunForSpec::fromBson)
            .disallowUnknownFields()
            .optional()
            .withDefault(VectorIndexRunForSpec.DEFAULT);

    static final Field.Optional<StoredSourceDefinition> STORED_SOURCE =
        Field.builder("storedSource")
            .classField(StoredSourceDefinition::fromBson)
            .optional()
            .noDefault();

    static final Field.Optional<FieldPath> NESTED_ROOT =
        Field.builder("nestedRoot")
            .classField(FieldPathField::parse, FieldPathField::encode)
            .optional()
            .noDefault();
  }

  private final List<VectorIndexFieldDefinition> fields;
  private final int numPartitions;
  private final VectorIndexRunForSpec runFor;
  private final Optional<StoredSourceDefinition> storedSource;
  private final Optional<FieldPath> nestedRoot;

  public VectorIndexSpec(
      List<VectorIndexFieldDefinition> fields,
      int numPartitions,
      VectorIndexRunForSpec runFor,
      Optional<StoredSourceDefinition> storedSource,
      Optional<FieldPath> nestedRoot) {
    this.fields = fields;
    this.numPartitions = numPartitions;
    this.runFor = runFor;
    this.storedSource = storedSource;
    this.nestedRoot = nestedRoot;
  }

  @Override
  public Type getType() {
    return Type.VECTOR_SEARCH;
  }

  @Override
  public List<IndexFormatVersion> getIndexFormatVersions() {
    int from = this.runFor.getIndexFormatVersion().getFrom();
    int to = this.runFor.getIndexFormatVersion().getTo();
    return IntStream.rangeClosed(from, to).mapToObj(IndexFormatVersion::create).toList();
  }

  @Override
  public int getNumPartitions() {
    return this.numPartitions;
  }

  @Override
  public List<Integer> getIndexFeatureVersions() {
    int from = this.runFor.getIndexFeatureVersion().getFrom();
    int to = this.runFor.getIndexFeatureVersion().getTo();
    return IntStream.rangeClosed(from, to).boxed().toList();
  }

  public VectorIndexRunForSpec getRunFor() {
    return this.runFor;
  }

  public Optional<StoredSourceDefinition> getStoredSource() {
    return this.storedSource;
  }

  public Optional<FieldPath> getNestedRoot() {
    return this.nestedRoot;
  }

  /** Builds IndexSpec from Bson. */
  public static VectorIndexSpec fromBson(DocumentParser parser) throws BsonParseException {
    return new VectorIndexSpec(
        parser.getField(Fields.FIELDS).unwrap(),
        parser.getField(IndexDefinition.Fields.NUM_PARTITIONS).unwrap(),
        parser.getField(Fields.RUN_FOR).unwrap(),
        parser.getField(Fields.STORED_SOURCE).unwrap(),
        parser.getField(Fields.NESTED_ROOT).unwrap());
  }

  @Override
  public BsonDocument toBson() {
    var builder = BsonDocumentBuilder.builder();
    builder.field(Fields.FIELDS, this.fields);
    builder.field(Fields.RUN_FOR, this.runFor);
    this.storedSource.ifPresent(
        storedSource -> builder.field(Fields.STORED_SOURCE, Optional.of(storedSource)));
    builder.field(Fields.NESTED_ROOT, this.nestedRoot);
    return builder.build();
  }

  public List<VectorIndexFieldDefinition> getFields() {
    return this.fields;
  }
}
