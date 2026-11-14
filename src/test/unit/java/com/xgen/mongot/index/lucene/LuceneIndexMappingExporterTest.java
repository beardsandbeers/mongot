package com.xgen.mongot.index.lucene;

import static com.google.common.truth.Truth.assertThat;

import com.xgen.mongot.index.definition.DocumentFieldDefinition;
import com.xgen.mongot.index.definition.TypeSetDefinition;
import com.xgen.mongot.index.version.IndexFormatVersion;
import com.xgen.mongot.util.bson.parser.BsonDocumentParser;
import com.xgen.testing.mongot.index.definition.SearchIndexDefinitionBuilder;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.apache.lucene.index.DocValuesSkipIndexType;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.bson.BsonDocument;
import org.junit.Test;

public class LuceneIndexMappingExporterTest {
  @Test
  public void export_partitionUnion_collapsesPhysicalTypesAndExcludesInternalFields() {
    var definition = SearchIndexDefinitionBuilder.VALID_INDEX;

    var result =
        LuceneIndexMappingExporter.export(
            definition,
            IndexFormatVersion.CURRENT,
            List.of(
                infos("$type:string/mixed", "$type:double/mixed", "$type:date/date"),
                infos(
                    "$type:string/mixed",
                    "$type:int64Multiple/mixed",
                    "$type:doubleV2/mixed",
                    "$type:dateV2/date",
                    "$type:boolean/other",
                    "$meta/_id",
                    "$storedSource",
                    "$multi/mixed.keyword",
                    "$type:null/nullOnly")));

    assertThat(result.getBoolean("dynamic").getValue()).isFalse();
    var fields = result.getDocument("fields");
    assertThat(fields.keySet()).containsExactly("mixed", "date", "other");
    assertThat(
            fields.getArray("mixed").stream()
                .map(value -> value.asDocument().getString("type").getValue())
                .toList())
        .containsExactly("number", "string");
    assertThat(fields.getDocument("date").getString("type").getValue()).isEqualTo("date");
  }

  @Test
  public void export_dynamicTypeSet_preservesOptionsAndOnlyObservedTypes() throws Exception {
    TypeSetDefinition typeSet;
    try (var parser =
        BsonDocumentParser.fromRoot(
                BsonDocument.parse(
                    """
                    {name: 'custom', types: [
                      {type:'string', analyzer:'lucene.keyword',
                        multi:{simple:{type:'string', analyzer:'lucene.simple'}}},
                      {type:'token', normalizer:'lowercase'},
                      {type:'number', representation:'int64'}]}
                    """))
            .build()) {
      typeSet = TypeSetDefinition.fromBson(parser);
    }
    var definition =
        SearchIndexDefinitionBuilder.builder()
            .defaultMetadata()
            .mappings(mappings("{dynamic:{typeSet:'custom'}}"))
            .typeSets(typeSet)
            .build();

    var result =
        LuceneIndexMappingExporter.export(
            definition,
            IndexFormatVersion.CURRENT,
            List.of(infos("$type:string/object.value", "$type:token/object.value")));

    var object = result.getDocument("fields").getDocument("object");
    assertThat(object.getBoolean("dynamic").getValue()).isFalse();
    var value = object.getDocument("fields").getArray("value");
    assertThat(value).hasSize(2);
    assertThat(value.get(0).asDocument().getString("analyzer").getValue())
        .isEqualTo("lucene.keyword");
    assertThat(value.get(0).asDocument().getDocument("multi").containsKey("simple")).isTrue();
    assertThat(value.get(1).asDocument().getString("normalizer").getValue()).isEqualTo("lowercase");
  }

  @Test
  public void export_embeddedAndDocumentAtSamePath_keepsScopesSeparate() throws Exception {
    var definition =
        SearchIndexDefinitionBuilder.builder()
            .defaultMetadata()
            .mappings(
                mappings(
                    """
                    {dynamic:false, fields:{items:[
                      {type:'document', dynamic:true},
                      {type:'embeddedDocuments', dynamic:true, fields:{unused:{type:'number'}}}
                    ], explicit:{type:'string', analyzer:'lucene.keyword'}}}
                    """))
            .build();

    var result =
        LuceneIndexMappingExporter.export(
            definition,
            IndexFormatVersion.CURRENT,
            List.of(
                infos(
                    "$type:string/items.rootOnly",
                    "$embedded:5/items/$type:boolean/items.embeddedOnly")));

    var items = result.getDocument("fields").getArray("items");
    assertThat(items.get(0).asDocument().getDocument("fields").keySet())
        .containsExactly("rootOnly");
    assertThat(items.get(1).asDocument().getDocument("fields").keySet())
        .containsExactly("embeddedOnly", "unused");
    assertThat(items.get(0).asDocument().getBoolean("dynamic").getValue()).isFalse();
    assertThat(items.get(1).asDocument().getBoolean("dynamic").getValue()).isFalse();
    assertThat(
            result.getDocument("fields").getDocument("explicit").getString("analyzer").getValue())
        .isEqualTo("lucene.keyword");
  }

  private static DocumentFieldDefinition mappings(String json) throws Exception {
    try (var parser = BsonDocumentParser.fromRoot(BsonDocument.parse(json)).build()) {
      return DocumentFieldDefinition.fromBson(parser);
    }
  }

  private static FieldInfos infos(String... names) {
    return new FieldInfos(
        IntStream.range(0, names.length)
            .mapToObj(
                i ->
                    new FieldInfo(
                        names[i],
                        i,
                        false,
                        false,
                        false,
                        IndexOptions.NONE,
                        DocValuesType.NONE,
                        DocValuesSkipIndexType.NONE,
                        -1L,
                        Map.of(),
                        0,
                        0,
                        0,
                        0,
                        VectorEncoding.BYTE,
                        VectorSimilarityFunction.COSINE,
                        false,
                        false))
            .toArray(FieldInfo[]::new));
  }
}
