package com.xgen.mongot.index.definition;

import static com.xgen.testing.BsonDeserializationTestSuite.fromValue;
import static com.xgen.testing.BsonSerializationTestSuite.load;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.definition.AutocompleteFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.BooleanFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.DateFacetFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.DateFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.DocumentFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.FieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.GeoFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.KnnVectorFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.NumericFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.ObjectIdFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.SortableDateBetaV1FieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.SortableNumberBetaV1FieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.SortableStringBetaV1FieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.StringFacetFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.StringFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.TokenFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.UuidFieldDefinitionBuilder;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      FieldDefinitionTest.DeserializationTest.class,
      FieldDefinitionTest.SerializationTest.class,
      FieldDefinitionTest.DefinitionTest.class,
    })
public class FieldDefinitionTest {

  @RunWith(Parameterized.class)
  public static class DeserializationTest {

    private static final String SUITE_NAME = "field-deserialization";
    private static final BsonDeserializationTestSuite<FieldDefinition> TEST_SUITE =
        fromValue(DefinitionTests.RESOURCES_PATH, SUITE_NAME, FieldDefinition::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<FieldDefinition> testSpec;

    public DeserializationTest(
        BsonDeserializationTestSuite.TestSpecWrapper<FieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<FieldDefinition>> data() {
      return TEST_SUITE.withExamples(
          autocomplete(),
          bool(),
          date(),
          dateFacet(),
          document(),
          geo(),
          knnVector(),
          number(),
          numberFacet(),
          objectId(),
          sortableDateBetaV1(),
          sortableNumberBetaV1(),
          sortableStringBetaV1(),
          string(),
          stringFacet(),
          token(),
          uuid(),
          multipleDefinitions(),
          dateAndSortableDateBetaV1(),
          numberAndSortableNumberBetaV1(),
          stringAndSortableStringBetaV1());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> autocomplete() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "autocomplete",
          FieldDefinitionBuilder.builder()
              .autocomplete(
                  AutocompleteFieldDefinitionBuilder.builder()
                      .tokenizationStrategy(AutocompleteFieldDefinition.TokenizationStrategy.N_GRAM)
                      .build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> bool() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "boolean",
          FieldDefinitionBuilder.builder()
              .bool(BooleanFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> date() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "date",
          FieldDefinitionBuilder.builder()
              .date(DateFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> dateFacet() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "dateFacet",
          FieldDefinitionBuilder.builder()
              .dateFacet(DateFacetFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> document() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "document",
          FieldDefinitionBuilder.builder()
              .document(DocumentFieldDefinitionBuilder.builder().dynamic(false).build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> geo() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "geo",
          FieldDefinitionBuilder.builder()
              .geo(GeoFieldDefinitionBuilder.builder().indexShapes(false).build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> knnVector() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "knnVector",
          FieldDefinitionBuilder.builder()
              .knnVector(
                  KnnVectorFieldDefinitionBuilder.builder()
                      .dimensions(100)
                      .similarity(VectorSimilarity.COSINE)
                      .build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> number() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "number",
          FieldDefinitionBuilder.builder()
              .number(
                  NumericFieldDefinitionBuilder.builder()
                      .representation(NumericFieldOptions.Representation.INT64)
                      .buildNumberField())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> numberFacet() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "numberFacet",
          FieldDefinitionBuilder.builder()
              .numberFacet(
                  NumericFieldDefinitionBuilder.builder()
                      .representation(NumericFieldOptions.Representation.INT64)
                      .buildNumberFacetField())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> objectId() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "objectId",
          FieldDefinitionBuilder.builder()
              .objectid(ObjectIdFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> sortableDateBetaV1() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "sortableDateBetaV1",
          FieldDefinitionBuilder.builder()
              .sortableDateBetaV1(SortableDateBetaV1FieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> sortableNumberBetaV1() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "sortableNumberBetaV1",
          FieldDefinitionBuilder.builder()
              .sortableNumberBetaV1(SortableNumberBetaV1FieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> sortableStringBetaV1() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "sortableStringBetaV1",
          FieldDefinitionBuilder.builder()
              .sortableStringBetaV1(SortableStringBetaV1FieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> string() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "string",
          FieldDefinitionBuilder.builder()
              .string(StringFieldDefinitionBuilder.builder().store(false).build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> stringFacet() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "stringFacet",
          FieldDefinitionBuilder.builder()
              .stringFacet(StringFacetFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> token() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "token",
          FieldDefinitionBuilder.builder()
              .token(TokenFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> uuid() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "uuid",
          FieldDefinitionBuilder.builder()
              .uuid(UuidFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition> multipleDefinitions() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "multiple definitions",
          FieldDefinitionBuilder.builder()
              .autocomplete(
                  AutocompleteFieldDefinitionBuilder.builder()
                      .tokenizationStrategy(AutocompleteFieldDefinition.TokenizationStrategy.N_GRAM)
                      .analyzer("myAnalyzer")
                      .build())
              .date(DateFieldDefinitionBuilder.builder().build())
              .document(DocumentFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition>
        dateAndSortableDateBetaV1() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "date and sortableDateBetaV1",
          FieldDefinitionBuilder.builder()
              .date(DateFieldDefinitionBuilder.builder().build())
              .sortableDateBetaV1(SortableDateBetaV1FieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition>
        numberAndSortableNumberBetaV1() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "number and sortableNumberBetaV1",
          FieldDefinitionBuilder.builder()
              .number(NumericFieldDefinitionBuilder.builder().buildNumberField())
              .sortableNumberBetaV1(SortableNumberBetaV1FieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonDeserializationTestSuite.ValidSpec<FieldDefinition>
        stringAndSortableStringBetaV1() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "string and sortableStringBetaV1",
          FieldDefinitionBuilder.builder()
              .sortableStringBetaV1(SortableStringBetaV1FieldDefinitionBuilder.builder().build())
              .string(StringFieldDefinitionBuilder.builder().build())
              .build());
    }
  }

  @RunWith(Parameterized.class)
  public static class SerializationTest {

    private static final String SUITE_NAME = "field-serialization";
    private static final BsonSerializationTestSuite<FieldDefinition> TEST_SUITE =
        load(DefinitionTests.RESOURCES_PATH, SUITE_NAME, FieldDefinition::toBson);

    private final BsonSerializationTestSuite.TestSpec<FieldDefinition> testSpec;

    public SerializationTest(BsonSerializationTestSuite.TestSpec<FieldDefinition> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<FieldDefinition>> data() {
      return List.of(
          autocomplete(),
          bool(),
          date(),
          dateFacet(),
          document(),
          geo(),
          knnVector(),
          number(),
          numberFacet(),
          objectId(),
          sortableDateBetaV1(),
          sortableNumberBetaV1(),
          sortableStringBetaV1(),
          string(),
          stringFacet(),
          token(),
          uuid(),
          multipleDefinitions());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> autocomplete() {
      return BsonSerializationTestSuite.TestSpec.create(
          "autocomplete",
          FieldDefinitionBuilder.builder()
              .autocomplete(
                  AutocompleteFieldDefinitionBuilder.builder()
                      .tokenizationStrategy(
                          AutocompleteFieldDefinition.TokenizationStrategy.EDGE_GRAM)
                      .minGrams(12)
                      .maxGrams(19)
                      .foldDiacritics(true)
                      .build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> bool() {
      return BsonSerializationTestSuite.TestSpec.create(
          "boolean",
          FieldDefinitionBuilder.builder()
              .bool(BooleanFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> date() {
      return BsonSerializationTestSuite.TestSpec.create(
          "date",
          FieldDefinitionBuilder.builder()
              .date(DateFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> dateFacet() {
      return BsonSerializationTestSuite.TestSpec.create(
          "dateFacet",
          FieldDefinitionBuilder.builder()
              .dateFacet(DateFacetFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> document() {
      return BsonSerializationTestSuite.TestSpec.create(
          "document",
          FieldDefinitionBuilder.builder()
              .document(DocumentFieldDefinitionBuilder.builder().dynamic(false).build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> geo() {
      return BsonSerializationTestSuite.TestSpec.create(
          "geo",
          FieldDefinitionBuilder.builder()
              .geo(GeoFieldDefinitionBuilder.builder().indexShapes(false).build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> knnVector() {
      return BsonSerializationTestSuite.TestSpec.create(
          "knnVector",
          FieldDefinitionBuilder.builder()
              .knnVector(
                  KnnVectorFieldDefinitionBuilder.builder()
                      .dimensions(1000)
                      .similarity(VectorSimilarity.DOT_PRODUCT)
                      .build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> number() {
      return BsonSerializationTestSuite.TestSpec.create(
          "number",
          FieldDefinitionBuilder.builder()
              .number(
                  NumericFieldDefinitionBuilder.builder()
                      .representation(NumericFieldOptions.Representation.INT64)
                      .buildNumberField())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> numberFacet() {
      return BsonSerializationTestSuite.TestSpec.create(
          "numberFacet",
          FieldDefinitionBuilder.builder()
              .numberFacet(
                  NumericFieldDefinitionBuilder.builder()
                      .representation(NumericFieldOptions.Representation.INT64)
                      .buildNumberFacetField())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> objectId() {
      return BsonSerializationTestSuite.TestSpec.create(
          "objectId",
          FieldDefinitionBuilder.builder()
              .objectid(ObjectIdFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> sortableDateBetaV1() {
      return BsonSerializationTestSuite.TestSpec.create(
          "sortableDateBetaV1",
          FieldDefinitionBuilder.builder()
              .sortableDateBetaV1(SortableDateBetaV1FieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> sortableNumberBetaV1() {
      return BsonSerializationTestSuite.TestSpec.create(
          "sortableNumberBetaV1",
          FieldDefinitionBuilder.builder()
              .sortableNumberBetaV1(SortableNumberBetaV1FieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> sortableStringBetaV1() {
      return BsonSerializationTestSuite.TestSpec.create(
          "sortableStringBetaV1",
          FieldDefinitionBuilder.builder()
              .sortableStringBetaV1(SortableStringBetaV1FieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> string() {
      return BsonSerializationTestSuite.TestSpec.create(
          "string",
          FieldDefinitionBuilder.builder()
              .string(StringFieldDefinitionBuilder.builder().store(false).build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> stringFacet() {
      return BsonSerializationTestSuite.TestSpec.create(
          "stringFacet",
          FieldDefinitionBuilder.builder()
              .stringFacet(StringFacetFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> token() {
      return BsonSerializationTestSuite.TestSpec.create(
          "token",
          FieldDefinitionBuilder.builder()
              .token(TokenFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> uuid() {
      return BsonSerializationTestSuite.TestSpec.create(
          "uuid",
          FieldDefinitionBuilder.builder()
              .uuid(UuidFieldDefinitionBuilder.builder().build())
              .build());
    }

    private static BsonSerializationTestSuite.TestSpec<FieldDefinition> multipleDefinitions() {
      return BsonSerializationTestSuite.TestSpec.create(
          "multiple definitions",
          FieldDefinitionBuilder.builder()
              .autocomplete(
                  AutocompleteFieldDefinitionBuilder.builder()
                      .tokenizationStrategy(
                          AutocompleteFieldDefinition.TokenizationStrategy.EDGE_GRAM)
                      .minGrams(12)
                      .maxGrams(19)
                      .foldDiacritics(true)
                      .analyzer("myAnalyzer")
                      .build())
              .date(DateFieldDefinitionBuilder.builder().build())
              .document(DocumentFieldDefinitionBuilder.builder().dynamic(false).build())
              .build());
    }
  }

  public static class DefinitionTest {

    @Test
    public void testGetAutocompleteDefinition() {
      FieldDefinition definitionWithAutocomplete =
          FieldDefinitionBuilder.builder()
              .autocomplete(AutocompleteFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(AutocompleteFieldDefinitionBuilder.builder().build()),
          definitionWithAutocomplete.autocompleteFieldDefinition());

      FieldDefinition definitionWithoutAutocomplete = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(
          Optional.empty(), definitionWithoutAutocomplete.autocompleteFieldDefinition());
    }

    @Test
    public void testGetBooleanDefinition() {
      FieldDefinition definitionWithBoolean =
          FieldDefinitionBuilder.builder()
              .bool(BooleanFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(BooleanFieldDefinitionBuilder.builder().build()),
          definitionWithBoolean.booleanFieldDefinition());

      FieldDefinition definitionWithoutBoolean = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(Optional.empty(), definitionWithoutBoolean.booleanFieldDefinition());
    }

    @Test
    public void testGetDateDefinition() {
      FieldDefinition definitionWithDate =
          FieldDefinitionBuilder.builder()
              .date(DateFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(DateFieldDefinitionBuilder.builder().build()),
          definitionWithDate.dateFieldDefinition());

      FieldDefinition definitionWithoutDate = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(Optional.empty(), definitionWithoutDate.dateFieldDefinition());
    }

    @Test
    public void testGetDateFacetDefinition() {
      FieldDefinition definitionWithDateFacet =
          FieldDefinitionBuilder.builder()
              .dateFacet(DateFacetFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(DateFacetFieldDefinitionBuilder.builder().build()),
          definitionWithDateFacet.dateFacetFieldDefinition());

      FieldDefinition definitionWithoutDate = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(Optional.empty(), definitionWithoutDate.dateFacetFieldDefinition());
    }

    @Test
    public void testGetDocumentDefinition() {
      FieldDefinition definitionWithDocument =
          FieldDefinitionBuilder.builder()
              .document(DocumentFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(DocumentFieldDefinitionBuilder.builder().build()),
          definitionWithDocument.documentFieldDefinition());

      FieldDefinition definitionWithoutDocument = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(Optional.empty(), definitionWithoutDocument.documentFieldDefinition());
    }

    @Test
    public void testGetGeoDefinition() {
      FieldDefinition definitionWithGeo =
          FieldDefinitionBuilder.builder().geo(GeoFieldDefinitionBuilder.builder().build()).build();
      Assert.assertEquals(
          Optional.of(GeoFieldDefinitionBuilder.builder().build()),
          definitionWithGeo.geoFieldDefinition());

      FieldDefinition definitionWithoutGeo = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(Optional.empty(), definitionWithoutGeo.geoFieldDefinition());
    }

    @Test
    public void testGetNumberDefinition() {
      FieldDefinition definitionWithNumber =
          FieldDefinitionBuilder.builder()
              .number(NumericFieldDefinitionBuilder.builder().buildNumberField())
              .build();
      Assert.assertEquals(
          Optional.of(NumericFieldDefinitionBuilder.builder().buildNumberField()),
          definitionWithNumber.numberFieldDefinition());

      FieldDefinition definitionWithoutNumeric = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(Optional.empty(), definitionWithoutNumeric.numberFieldDefinition());
    }

    @Test
    public void testGetNumberFacetDefinition() {
      FieldDefinition definitionWithNumberFacet =
          FieldDefinitionBuilder.builder()
              .numberFacet(NumericFieldDefinitionBuilder.builder().buildNumberFacetField())
              .build();
      Assert.assertEquals(
          Optional.of(NumericFieldDefinitionBuilder.builder().buildNumberFacetField()),
          definitionWithNumberFacet.numberFacetFieldDefinition());

      FieldDefinition definitionWithoutNumeric = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(Optional.empty(), definitionWithoutNumeric.numberFacetFieldDefinition());
    }

    @Test
    public void testGetObjectIdDefinition() {
      FieldDefinition definitionWithObjectId =
          FieldDefinitionBuilder.builder()
              .objectid(ObjectIdFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(ObjectIdFieldDefinitionBuilder.builder().build()),
          definitionWithObjectId.objectIdFieldDefinition());

      FieldDefinition definitionWithoutObjectId = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(Optional.empty(), definitionWithoutObjectId.objectIdFieldDefinition());
    }

    @Test
    public void testGetSortableDateBetaV1Definition() {
      FieldDefinition definitionWithSortableDateBetaV1 =
          FieldDefinitionBuilder.builder()
              .sortableDateBetaV1(SortableDateBetaV1FieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(SortableDateBetaV1FieldDefinitionBuilder.builder().build()),
          definitionWithSortableDateBetaV1.sortableDateBetaV1FieldDefinition());

      FieldDefinition definitionWithoutSortableDateBetaV1 =
          FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(
          Optional.empty(),
          definitionWithoutSortableDateBetaV1.sortableDateBetaV1FieldDefinition());
    }

    @Test
    public void testGetSortableNumberBetaV1Definition() {
      FieldDefinition definitionWithSortableNumberBetaV1 =
          FieldDefinitionBuilder.builder()
              .sortableNumberBetaV1(SortableNumberBetaV1FieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(SortableNumberBetaV1FieldDefinitionBuilder.builder().build()),
          definitionWithSortableNumberBetaV1.sortableNumberBetaV1FieldDefinition());

      FieldDefinition definitionWithoutSortableNumberBetaV1 =
          FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(
          Optional.empty(),
          definitionWithoutSortableNumberBetaV1.sortableNumberBetaV1FieldDefinition());
    }

    @Test
    public void testGetSortableStringBetaV1Definition() {
      FieldDefinition definitionWithSortableStringBetaV1 =
          FieldDefinitionBuilder.builder()
              .sortableStringBetaV1(SortableStringBetaV1FieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(SortableStringBetaV1FieldDefinitionBuilder.builder().build()),
          definitionWithSortableStringBetaV1.sortableStringBetaV1FieldDefinition());

      FieldDefinition definitionWithoutSortableStringBetaV1 =
          FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(
          Optional.empty(),
          definitionWithoutSortableStringBetaV1.sortableStringBetaV1FieldDefinition());
    }

    @Test
    public void testGetStringDefinition() {
      FieldDefinition definitionWithString =
          FieldDefinitionBuilder.builder()
              .string(StringFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(StringFieldDefinitionBuilder.builder().build()),
          definitionWithString.stringFieldDefinition());

      FieldDefinition definitionWithoutString = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(Optional.empty(), definitionWithoutString.stringFieldDefinition());
    }

    @Test
    public void testGetStringFacetDefinition() {
      FieldDefinition definitionWithStringFacet =
          FieldDefinitionBuilder.builder()
              .stringFacet(StringFacetFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(StringFacetFieldDefinitionBuilder.builder().build()),
          definitionWithStringFacet.stringFacetFieldDefinition());

      FieldDefinition definitionWithoutStringFacet = FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(
          Optional.empty(), definitionWithoutStringFacet.stringFacetFieldDefinition());
    }

    @Test
    public void testGetTokenFieldDefinition() {
      FieldDefinition definitionWithTokenField =
          FieldDefinitionBuilder.builder()
              .token(TokenFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(TokenFieldDefinitionBuilder.builder().build()),
          definitionWithTokenField.tokenFieldDefinition());

      FieldDefinition definitionWithoutTokenFieldDefinition =
          FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(
          Optional.empty(), definitionWithoutTokenFieldDefinition.tokenFieldDefinition());
    }

    @Test
    public void testGetUuidFieldDefinition() {
      FieldDefinition definitionWithUuidField =
          FieldDefinitionBuilder.builder()
              .uuid(UuidFieldDefinitionBuilder.builder().build())
              .build();
      Assert.assertEquals(
          Optional.of(UuidFieldDefinitionBuilder.builder().build()),
          definitionWithUuidField.uuidFieldDefinition());

      FieldDefinition definitionWithoutUuidFieldDefinition =
          FieldDefinitionBuilder.builder().build();
      Assert.assertEquals(
          Optional.empty(), definitionWithoutUuidFieldDefinition.uuidFieldDefinition());
    }

    @Test
    public void testHasScalarFieldDefinitions() {

      Stream.of(
              FieldDefinitionBuilder.builder()
                  .autocomplete(AutocompleteFieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder()
                  .bool(BooleanFieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder().date(DateFieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder().geo(GeoFieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder()
                  .number(NumericFieldDefinitionBuilder.builder().buildNumberField()),
              FieldDefinitionBuilder.builder()
                  .objectid(ObjectIdFieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder()
                  .sortableDateBetaV1(SortableDateBetaV1FieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder()
                  .sortableNumberBetaV1(
                      SortableNumberBetaV1FieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder()
                  .sortableStringBetaV1(
                      SortableStringBetaV1FieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder()
                  .string(StringFieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder()
                  .stringFacet(StringFacetFieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder().token(TokenFieldDefinitionBuilder.builder().build()),
              FieldDefinitionBuilder.builder().uuid(UuidFieldDefinitionBuilder.builder().build()))
          .map(FieldDefinitionBuilder::build)
          .forEach(
              scalarDefinition -> Assert.assertTrue(scalarDefinition.hasScalarFieldDefinitions()));

      FieldDefinition documentFieldDefinition =
          FieldDefinitionBuilder.builder()
              .document(DocumentFieldDefinitionBuilder.builder().build())
              .build();

      Assert.assertFalse(documentFieldDefinition.hasScalarFieldDefinitions());

      FieldDefinition mixedFieldDefinition =
          FieldDefinitionBuilder.builder()
              .string(StringFieldDefinitionBuilder.builder().build())
              .document(DocumentFieldDefinitionBuilder.builder().build())
              .build();

      Assert.assertTrue(mixedFieldDefinition.hasScalarFieldDefinitions());
    }

    @Test
    public void testEquals() {
      TestUtils.assertEqualityGroups(
          () -> FieldDefinitionBuilder.builder().build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .autocomplete(AutocompleteFieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .bool(BooleanFieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .date(DateFieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .document(DocumentFieldDefinitionBuilder.builder().dynamic(false).build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .document(DocumentFieldDefinitionBuilder.builder().dynamic(true).build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .geo(GeoFieldDefinitionBuilder.builder().indexShapes(false).build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .geo(GeoFieldDefinitionBuilder.builder().indexShapes(true).build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .number(
                      NumericFieldDefinitionBuilder.builder()
                          .indexDoubles(false)
                          .buildNumberField())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .number(
                      NumericFieldDefinitionBuilder.builder().indexDoubles(true).buildNumberField())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .objectid(ObjectIdFieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .sortableDateBetaV1(SortableDateBetaV1FieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .sortableNumberBetaV1(
                      SortableNumberBetaV1FieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .sortableStringBetaV1(
                      SortableStringBetaV1FieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .string(
                      StringFieldDefinitionBuilder.builder()
                          .norms(StringFieldDefinition.NormsOptions.OMIT)
                          .build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .stringFacet(StringFacetFieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .token(TokenFieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .uuid(UuidFieldDefinitionBuilder.builder().build())
                  .build(),
          () ->
              FieldDefinitionBuilder.builder()
                  .autocomplete(AutocompleteFieldDefinitionBuilder.builder().build())
                  .date(DateFieldDefinitionBuilder.builder().build())
                  .bool(BooleanFieldDefinitionBuilder.builder().build())
                  .document(DocumentFieldDefinitionBuilder.builder().build())
                  .geo(GeoFieldDefinitionBuilder.builder().build())
                  .number(NumericFieldDefinitionBuilder.builder().buildNumberField())
                  .objectid(ObjectIdFieldDefinitionBuilder.builder().build())
                  .string(StringFieldDefinitionBuilder.builder().build())
                  .stringFacet(StringFacetFieldDefinitionBuilder.builder().build())
                  .token(TokenFieldDefinitionBuilder.builder().build())
                  .uuid(UuidFieldDefinitionBuilder.builder().build())
                  .build());
    }
  }
}
