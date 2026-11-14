package com.xgen.mongot.standalone.server;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.grpc.CatalogServiceGrpc;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DeleteRequest;
import com.xgen.mongot.standalone.grpc.DocumentServiceGrpc;
import com.xgen.mongot.standalone.grpc.DocumentWriteConcern;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.GetIndexFieldStatsRequest;
import com.xgen.mongot.standalone.grpc.GetIndexMappingsRequest;
import com.xgen.mongot.standalone.grpc.GetIndexRequest;
import com.xgen.mongot.standalone.grpc.GetIndexStatsRequest;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.IndexFieldStatsResponse;
import com.xgen.mongot.standalone.grpc.IndexStats;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.ListIndexesRequest;
import com.xgen.mongot.standalone.grpc.QueryRequest;
import com.xgen.mongot.standalone.grpc.QueryServiceGrpc;
import com.xgen.mongot.standalone.grpc.StatsCollectionState;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.grpc.UpsertRequest;
import com.xgen.mongot.standalone.runtime.StandaloneRuntime;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StandaloneGrpcServerTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void getIndexStats_emptyAndMutations_reportsLiveDocumentsAndOptionalAverages()
      throws Exception {
    try (var f = new Fixture(data())) {
      String id = f.create("stats");

      var empty = f.stats(id);
      assertThat(empty.getDocumentCount()).isEqualTo(0);
      assertThat(empty.hasAverageIndexBytesPerDocument()).isFalse();
      assertThat(empty.hasAverageDirectoryBytesPerDocument()).isFalse();
      f.upsert(id, "{_id:'a',title:'coffee'}");
      f.upsert(id, "{_id:'b',title:'tea'}");
      f.upsert(id, "{_id:'a',title:'replacement'}");
      var two = f.stats(id);

      assertThat(two.getDocumentCount()).isEqualTo(2);
      assertThat(two.getLuceneDocumentCount()).isEqualTo(2);
      assertThat(two.getIndexSizeBytes()).isGreaterThan(0);
      assertThat(two.getAverageIndexBytesPerDocument()).isEqualTo(two.getIndexSizeBytes() / 2.0);
      assertThat(two.getDirectorySizeBytes().hasValue()).isTrue();
      assertThat(two.getMemory().getAccountedWriterBufferHeapBytes().hasValue()).isTrue();
      f.docs.delete(
          DeleteRequest.newBuilder().setIndexId(id).addIdsBson(bson("{_id:'b'}")).build());
      assertThat(f.stats(id).getDocumentCount()).isEqualTo(1);
    }
  }

  @Test
  public void getIndexFieldStats_partitionedEmbeddedMixedTypes_reconcilesLedgerAndPreservesPages()
      throws Exception {
    try (var f = new Fixture(data())) {
      String id =
          f.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName("field-stats")
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(
                          bson(
                              """
                              {numPartitions:2,mappings:{dynamic:true,fields:{
                                unused:{type:'number'},
                                items:{type:'embeddedDocuments',dynamic:true}
                              }}}
                              """))
                      .build())
              .getIndexId();
      f.ready(id, 1);
      f.upsert(id, "{_id:'a',mixed:17,items:[{value:'one'},{value:'two'}]}");
      f.upsert(id, "{_id:'b',mixed:'seventeen',items:[{value:'three'}]}");

      var full = f.fieldStats(id, 100);

      assertThat(full.getIndexStats().getDocumentCount()).isEqualTo(2);
      assertThat(full.getIndexStats().getLuceneDocumentCount()).isEqualTo(5);
      assertThat(
              full.getTotalAttributedDiskBytes()
                  + full.getSharedDiskBytes()
                  + full.getUnattributedDiskBytes())
          .isEqualTo(full.getIndexStats().getIndexSizeBytes());
      assertThat(
              full.getFieldsList().stream()
                  .mapToLong(field -> field.getAttributedDiskBytes().getValue())
                  .sum())
          .isEqualTo(full.getTotalAttributedDiskBytes());
      var mixed =
          full.getFieldsList().stream()
              .filter(field -> field.getPath().equals("mixed"))
              .findFirst()
              .orElseThrow();
      assertThat(mixed.getRepresentationsList().stream().map(rep -> rep.getMappingType()).toList())
          .containsAtLeast("number", "string");
      var unused =
          full.getFieldsList().stream()
              .filter(field -> field.getPath().equals("unused"))
              .findFirst()
              .orElseThrow();
      assertThat(unused.getConfigured()).isTrue();
      assertThat(unused.getObserved()).isFalse();
      assertThat(unused.getAttributedDiskBytes().getValue()).isEqualTo(0);
      var first = f.fieldStats(id, 1);
      assertThat(first.getFieldsCount()).isEqualTo(1);
      assertThat(first.getNextPageToken()).isNotEmpty();
      f.upsert(id, "{_id:'c',newField:'after snapshot'}");
      var second =
          f.catalog.getIndexFieldStats(
              GetIndexFieldStatsRequest.newBuilder()
                  .setIndexId(id)
                  .setPageSize(1)
                  .setPageToken(first.getNextPageToken())
                  .build());
      assertThat(second.getIndexStats().getSnapshotId())
          .isEqualTo(first.getIndexStats().getSnapshotId());
      assertThat(second.getIndexStats().getDocumentCount()).isEqualTo(2);
      assertThat(f.stats(id).getDocumentCount()).isEqualTo(3);
      var selected =
          f.catalog.getIndexFieldStats(
              GetIndexFieldStatsRequest.newBuilder().setIndexId(id).addPaths("mixed").build());
      assertThat(selected.getFieldsCount()).isEqualTo(1);
      assertThat(selected.getTotalAttributedDiskBytes())
          .isEqualTo(full.getTotalAttributedDiskBytes());
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexFieldStats(
                              GetIndexFieldStatsRequest.newBuilder()
                                  .setIndexId(id)
                                  .setPageSize(2)
                                  .setPageToken(first.getNextPageToken())
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  public void getIndexStats_nestedVectors_excludesChildDocumentsFromSourceCount() throws Exception {
    try (var f = new Fixture(data())) {
      String id =
          f.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName("nested-vector-stats")
                      .setType(IndexType.INDEX_TYPE_VECTOR_SEARCH)
                      .setDefinitionBson(
                          bson(
                              """
                              {numPartitions:2,nestedRoot:'sections',fields:[
                                {type:'vector',path:'sections.embedding',numDimensions:3,
                                 similarity:'euclidean'}]}
                              """))
                      .build())
              .getIndexId();
      f.ready(id, 1);
      f.upsert(id, "{_id:'a',sections:[{embedding:[1,0,0]},{embedding:[0,1,0]}]}");
      f.upsert(id, "{_id:'b',sections:[{embedding:[0,0,1]}]}");

      var fields = f.fieldStats(id, 100);

      assertThat(fields.getIndexStats().getDocumentCount()).isEqualTo(2);
      assertThat(fields.getIndexStats().getLuceneDocumentCount()).isEqualTo(5);
      assertThat(fields.getFieldsList().stream().map(field -> field.getPath()).toList())
          .contains("sections.embedding");
    }
  }

  @Test
  public void getIndexStats_invalidTargetsAndTokens_returnsGrpcErrors() throws Exception {
    try (var f = new Fixture(data())) {
      String id = f.create("stats-errors");

      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexStats(
                              GetIndexStatsRequest.newBuilder().setIndexId("bad").build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexStats(
                              GetIndexStatsRequest.newBuilder()
                                  .setIndexId(id)
                                  .setGenerationId("stale")
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexFieldStats(
                              GetIndexFieldStatsRequest.newBuilder()
                                  .setIndexId(id)
                                  .setPageSize(501)
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexFieldStats(
                              GetIndexFieldStatsRequest.newBuilder()
                                  .setIndexId(id)
                                  .setPageToken("invalid")
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexFieldStats(
                              GetIndexFieldStatsRequest.newBuilder()
                                  .setIndexId(id)
                                  .setPageToken("00000000-0000-0000-0000-000000000000")
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }
  }

  @Test
  public void getIndexMappings_mixedNestedFields_canCreateAndQueryStaticIndex() throws Exception {
    try (var f = new Fixture(data())) {
      String id =
          f.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName("dynamic")
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(
                          bson(
                              """
                              {analyzer:'lucene.standard', numPartitions:2, storedSource:true,
                               mappings:{dynamic:true, fields:{
                                 title:{type:'string', analyzer:'lucene.keyword',
                                   multi:{simple:{type:'string', analyzer:'lucene.simple'}}},
                                 unused:{type:'number'},
                                 blocked:{type:'document', dynamic:false,
                                   fields:{kept:{type:'boolean'}}},
                                 items:{type:'embeddedDocuments', dynamic:true}
                               }}}
                              """))
                      .build())
              .getIndexId();
      var ready = f.ready(id, 1);
      f.upsert(
          id,
          """
          {_id:'a', title:'coffee', mixed:42, nested:{value:'tea'},
           items:[{score:1, detail:{text:'embedded'}}], blocked:{secret:'hidden', kept:true},
           numbers:[1, 2.5], date:{$date:'2026-01-01T00:00:00Z'},
           objectId:{$oid:'507f1f77bcf86cd799439011'},
           uuid:{$binary:{base64:'AAAAAAAAAAAAAAAAAAAAAA==', subType:'04'}}, flag:true}
          """);
      f.upsert(
          id,
          "{_id:'b', title:'tea', mixed:'forty two', nested:'also a scalar',"
              + " items:[{score:'high'}]}");

      var exported =
          f.catalog.getIndexMappings(
              GetIndexMappingsRequest.newBuilder()
                  .setIndexId(id)
                  .setGenerationId(ready.getGenerationId())
                  .build());

      assertThat(exported.getGenerationId()).isEqualTo(ready.getGenerationId());
      assertThat(exported.getActiveRevision()).isEqualTo(1);
      var mapping = BsonDocument.parse(exported.getMappingsJson());
      var fields = mapping.getDocument("fields");
      assertThat(mapping.getBoolean("dynamic").getValue()).isFalse();
      assertThat(
              fields.getArray("mixed").stream()
                  .map(v -> v.asDocument().getString("type").getValue())
                  .toList())
          .containsExactly("number", "string");
      assertThat(
              fields.getArray("nested").stream()
                  .map(v -> v.asDocument().getString("type").getValue())
                  .toList())
          .containsExactly("document", "string");
      assertThat(fields.getDocument("title").getString("analyzer").getValue())
          .isEqualTo("lucene.keyword");
      assertThat(fields.getDocument("title").getDocument("multi").containsKey("simple")).isTrue();
      assertThat(fields.getDocument("unused").getString("type").getValue()).isEqualTo("number");
      assertThat(fields.getDocument("blocked").getDocument("fields").keySet())
          .containsExactly("kept");
      assertThat(fields.getDocument("items").getBoolean("dynamic").getValue()).isFalse();
      assertThat(fields.getDocument("items").getDocument("fields").getArray("score")).hasSize(2);
      assertThat(fields.getDocument("date").getString("type").getValue()).isEqualTo("date");
      assertThat(fields.getDocument("objectId").getString("type").getValue()).isEqualTo("objectId");
      assertThat(fields.getDocument("uuid").getString("type").getValue()).isEqualTo("uuid");
      assertThat(fields.getDocument("flag").getString("type").getValue()).isEqualTo("boolean");
      var definition = BsonDocument.parse(exported.getDefinitionJson());
      assertThat(definition.getDocument("mappings")).isEqualTo(mapping);
      assertThat(definition.getBoolean("storedSource").getValue()).isTrue();
      String staticId =
          f.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName("static")
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(bson(exported.getDefinitionJson()))
                      .build())
              .getIndexId();
      f.ready(staticId, 1);
      f.upsert(
          staticId,
          "{_id:'a', title:'coffee', mixed:42, nested:{value:'tea'}, items:[{score:1}],"
              + " unexpected:'ignored'}");
      f.upsert(
          staticId,
          "{_id:'b', title:'tea', mixed:'forty two', nested:'also a scalar',"
              + " items:[{score:'high'}]}");

      assertThat(f.results(staticId, "coffee").getArray("results")).hasSize(1);
      assertThat(f.query(staticId, "{equals:{path:'mixed', value:42}}").getArray("results"))
          .hasSize(1);
      assertThat(f.query(staticId, "{text:{path:'mixed', query:'forty'}}").getArray("results"))
          .hasSize(1);
      assertThat(f.query(staticId, "{text:{path:'nested.value', query:'tea'}}").getArray("results"))
          .hasSize(1);
      assertThat(
              f.query(
                      staticId,
                      """
                      {embeddedDocument:{path:'items',
                        operator:{text:{path:'items.score',query:'high'}}}}
                      """)
                  .getArray("results"))
          .hasSize(1);
      assertThat(
              f.catalog
                  .getIndexMappings(
                      GetIndexMappingsRequest.newBuilder().setIndexId(staticId).build())
                  .getMappingsJson())
          .doesNotContain("unexpected");
    }
  }

  @Test
  public void getIndexMappings_customTypeSet_preservesDynamicOptionsInStaticIndex()
      throws Exception {
    try (var f = new Fixture(data())) {
      String id =
          f.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName("type-set")
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(
                          bson(
                              """
                              {analyzers:[{name:'casefold',tokenizer:{type:'keyword'},
                                tokenFilters:[{type:'lowercase'}]}],
                               typeSets:[{name:'sample',types:[
                                 {type:'string',analyzer:'casefold'},
                                 {type:'number',representation:'int64'},
                                 {type:'token',normalizer:'lowercase'},
                                 {type:'autocomplete',minGrams:2,maxGrams:6},
                                 {type:'geo',indexShapes:true}]}],
                               mappings:{dynamic:{typeSet:'sample'}}}
                              """))
                      .build())
              .getIndexId();
      f.ready(id, 1);
      f.upsert(
          id,
          "{_id:'one', object:{label:'COFFEE',amount:7},"
              + " location:{type:'Point',coordinates:[1.0,2.0]}}");

      var exported =
          f.catalog.getIndexMappings(GetIndexMappingsRequest.newBuilder().setIndexId(id).build());

      var definition = BsonDocument.parse(exported.getDefinitionJson());
      assertThat(definition.getArray("analyzers").get(0).asDocument().getString("name").getValue())
          .isEqualTo("casefold");
      var fields = definition.getDocument("mappings").getDocument("fields");
      var object = fields.getDocument("object");
      assertThat(object.getBoolean("dynamic").getValue()).isFalse();
      assertThat(
              object.getDocument("fields").getArray("label").stream()
                  .map(v -> v.asDocument().getString("type").getValue())
                  .toList())
          .containsExactly("autocomplete", "string", "token");
      assertThat(
              object
                  .getDocument("fields")
                  .getDocument("amount")
                  .getString("representation")
                  .getValue())
          .isEqualTo("int64");
      assertThat(
              fields.getArray("location").stream()
                  .map(v -> v.asDocument().getString("type").getValue())
                  .toList())
          .containsExactly("document", "geo");
      String staticId =
          f.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName("static-type-set")
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(bson(exported.getDefinitionJson()))
                      .build())
              .getIndexId();
      f.ready(staticId, 1);
      f.upsert(
          staticId,
          "{_id:'one', object:{label:'COFFEE',amount:7},"
              + " location:{type:'Point',coordinates:[1.0,2.0]}}");

      assertThat(
              f.query(staticId, "{text:{path:'object.label',query:'coffee'}}").getArray("results"))
          .hasSize(1);
      assertThat(
              f.query(staticId, "{equals:{path:'object.label',value:'coffee'}}")
                  .getArray("results"))
          .hasSize(1);
      assertThat(
              f.query(staticId, "{autocomplete:{path:'object.label',query:'cof'}}")
                  .getArray("results"))
          .hasSize(1);
      assertThat(f.query(staticId, "{equals:{path:'object.amount',value:7}}").getArray("results"))
          .hasSize(1);
    }
  }

  @Test
  public void getIndexMappings_restart_recoversDynamicFieldsFromLucene() throws Exception {
    Path data = data();
    String id;
    String mappings;
    try (var f = new Fixture(data)) {
      id = f.create("persistent-mapping");
      f.upsert(id, "{_id:'a', nested:{value:17}}");
      mappings =
          f.catalog
              .getIndexMappings(GetIndexMappingsRequest.newBuilder().setIndexId(id).build())
              .getMappingsJson();
    }

    try (var f = new Fixture(data)) {
      f.ready(id, 1);

      assertThat(
              f.catalog
                  .getIndexMappings(GetIndexMappingsRequest.newBuilder().setIndexId(id).build())
                  .getMappingsJson())
          .isEqualTo(mappings);
    }
  }

  @Test
  public void getIndexMappings_invalidTargets_returnsGrpcErrors() throws Exception {
    try (var f = new Fixture(data())) {
      String id = f.create("mapping-errors");

      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexMappings(
                              GetIndexMappingsRequest.newBuilder().setIndexId("bad-id").build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexMappings(
                              GetIndexMappingsRequest.newBuilder()
                                  .setIndexId("507f1f77bcf86cd799439011")
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.NOT_FOUND);
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexMappings(
                              GetIndexMappingsRequest.newBuilder()
                                  .setIndexId(id)
                                  .setGenerationId("stale")
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
      var vector =
          f.catalog.createIndex(
              CreateIndexRequest.newBuilder()
                  .setName("vector-mapping")
                  .setType(IndexType.INDEX_TYPE_VECTOR_SEARCH)
                  .setDefinitionBson(
                      bson(
                          """
                          {fields:[{type:'vector',path:'v',numDimensions:3,similarity:'cosine'}]}
                          """))
                  .build());
      f.ready(vector.getIndexId(), 1);
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexMappings(
                              GetIndexMappingsRequest.newBuilder()
                                  .setIndexId(vector.getIndexId())
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
      f.catalog.dropIndex(
          DropIndexRequest.newBuilder().setIndexId(id).setExpectedRevision(1).build());
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.catalog.getIndexMappings(
                              GetIndexMappingsRequest.newBuilder().setIndexId(id).build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.NOT_FOUND);
    }
  }

  @Test
  public void push_upsertsAndDeletesOnlyTheSelectedIndex() throws Exception {
    try (var f = new Fixture(data())) {
      String first = f.create("first");
      String second = f.create("second");
      f.upsert(first, "{_id: 'a', title: 'coffee', privateField: 'not-stored'}");
      assertThat(f.results(first, "coffee").getArray("results")).hasSize(1);
      assertThat(f.results(second, "coffee").getArray("results")).isEmpty();
      f.upsert(first, "{_id: 'a', title: 'tea'}");
      assertThat(f.results(first, "coffee").getArray("results")).isEmpty();
      assertThat(f.results(first, "tea").getArray("results")).hasSize(1);
      var deleted =
          f.docs.delete(
              DeleteRequest.newBuilder()
                  .setIndexId(first)
                  .addIdsBson(bson("{_id: 'a'}"))
                  .addIdsBson(bson("{_id: 'missing'}"))
                  .build());
      assertThat(deleted.getProcessedCount()).isEqualTo(2);
      assertThat(f.results(first, "tea").getArray("results")).isEmpty();
    }
  }

  @Test
  public void restart_preservesCommittedLuceneDataAndIndexDefinitionsOnly() throws Exception {
    Path data = data();
    String id;
    try (var f = new Fixture(data)) {
      id = f.create("persistent");
      f.docs.upsert(
          UpsertRequest.newBuilder()
              .setIndexId(id)
              .setWriteConcern(DocumentWriteConcern.DOCUMENT_WRITE_CONCERN_COMMITTED)
              .addDocumentsBson(
                  bson("{_id: 'a', title: 'coffee', privateField: 'secret-content-marker'}"))
              .build());
    }
    try (var f = new Fixture(data)) {
      f.ready(id, 1);
      var result = f.results(id, "coffee").getArray("results");
      assertThat(result).hasSize(1);
      assertThat(result.get(0).asDocument().containsKey("storedSource")).isFalse();
      assertThat(f.catalog.listIndexes(ListIndexesRequest.getDefaultInstance()).getIndexesCount())
          .isEqualTo(1);
    }
    assertThat(Files.exists(data.resolve("standalone.sqlite"))).isFalse();
    assertThat(Files.readString(data.resolve("indexes.json")))
        .doesNotContain("secret-content-marker");
    try (var paths = Files.list(data)) {
      assertThat(paths.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList())
          .containsExactly(
              "standalone.lock", "indexes.json", "configJournal.json", "dynamicFeatureFlags.json");
    }
  }

  @Test
  public void mappingUpdate_startsEmptyAndRequiresReingestion() throws Exception {
    try (var f = new Fixture(data())) {
      String id = f.create("schema");
      f.upsert(id, "{_id: 'a', title: 'coffee'}");
      String oldGeneration = f.ready(id, 1).getGenerationId();
      f.catalog.updateIndex(
          UpdateIndexRequest.newBuilder()
              .setIndexId(id)
              .setExpectedRevision(1)
              .setDefinitionBson(
                  bson("{mappings: {dynamic: false, fields: {title: {type: 'string'}}}}"))
              .build());
      var updated = f.ready(id, 2);
      assertThat(updated.getGenerationId()).isNotEqualTo(oldGeneration);
      assertThat(f.results(id, "coffee").getArray("results")).isEmpty();
      var stale =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  f.docs.upsert(
                      UpsertRequest.newBuilder()
                          .setIndexId(id)
                          .setGenerationId(oldGeneration)
                          .addDocumentsBson(bson("{_id: 'a', title: 'coffee'}"))
                          .build()));
      assertThat(stale.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
      f.upsert(id, "{_id: 'a', title: 'coffee'}");
      assertThat(f.results(id, "coffee").getArray("results")).hasSize(1);
    }
  }

  @Test
  public void newIndex_neverBackfillsContentFromAnotherIndex() throws Exception {
    try (var f = new Fixture(data())) {
      String first = f.create("first");
      f.upsert(first, "{_id: 'a', title: 'coffee'}");
      String second = f.create("second");
      assertThat(f.results(second, "coffee").getArray("results")).isEmpty();
      f.catalog.dropIndex(
          DropIndexRequest.newBuilder().setIndexId(first).setExpectedRevision(1).build());
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.docs.upsert(
                              UpsertRequest.newBuilder()
                                  .setIndexId(first)
                                  .addDocumentsBson(bson("{_id: 'a'}"))
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.NOT_FOUND);
      String replacement = f.create("first");
      assertThat(replacement).isNotEqualTo(first);
      assertThat(f.results(replacement, "coffee").getArray("results")).isEmpty();
    }
  }

  @Test
  public void validation_rejectsMalformedBatchBeforeWriting() throws Exception {
    try (var f = new Fixture(data())) {
      String id = f.create("validation");
      var error =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  f.docs.upsert(
                      UpsertRequest.newBuilder()
                          .setIndexId(id)
                          .addDocumentsBson(bson("{_id:'a',title:'coffee'}"))
                          .addDocumentsBson(bson("{title:'missing id'}"))
                          .build()));
      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(f.results(id, "coffee").getArray("results")).isEmpty();
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          f.docs.delete(
                              DeleteRequest.newBuilder()
                                  .setIndexId(id)
                                  .addIdsBson(bson("{_id:'a',other:1}"))
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  public void vectorPush_usesExistingVectorSchemaAndReader() throws Exception {
    com.xgen.mongot.index.lucene.vector.Similarity.load();
    try (var f = new Fixture(data())) {
      var index =
          f.catalog.createIndex(
              CreateIndexRequest.newBuilder()
                  .setName("vectors")
                  .setType(IndexType.INDEX_TYPE_VECTOR_SEARCH)
                  .setDefinitionBson(
                      bson(
                          "{fields:[{type:'vector',path:'embedding',"
                              + "numDimensions:3,similarity:'cosine'}]}"))
                  .build());
      String id = index.getIndexId();
      f.ready(id, 1);
      f.upsert(id, "{_id:'near',embedding:[1.0,0.0,0.0]}");
      var result =
          f.queries.vectorSearch(
              QueryRequest.newBuilder()
                  .setIndexId(id)
                  .setQueryBson(
                      bson("{path:'embedding',queryVector:[1.0,0.0,0.0],exact:true,limit:1}"))
                  .build());
      assertThat(new RawBsonDocument(result.getBatchBson().toByteArray()).getArray("results"))
          .hasSize(1);
    }
  }

  @Test
  public void ownershipLock_rejectsSecondRuntime() throws Exception {
    Path data = data();
    try (var f = new Fixture(data)) {
      assertThrows(
          java.io.IOException.class,
          () -> new StandaloneRuntime(StandaloneConfig.development(data, 0)));
    }
  }

  private Path data() {
    return this.folder.getRoot().toPath().resolve("data");
  }

  private static ByteString bson(String json) {
    return ByteString.copyFrom(StandaloneDefinitionValidator.encode(BsonDocument.parse(json)));
  }

  private static final class Fixture implements AutoCloseable {
    private final StandaloneRuntime runtime;
    private final StandaloneGrpcServer server;
    private final ManagedChannel channel;
    private final CatalogServiceGrpc.CatalogServiceBlockingStub catalog;
    private final DocumentServiceGrpc.DocumentServiceBlockingStub docs;
    private final QueryServiceGrpc.QueryServiceBlockingStub queries;

    private Fixture(Path data) throws Exception {
      this.runtime = new StandaloneRuntime(StandaloneConfig.development(data, 0));
      this.server = new StandaloneGrpcServer(this.runtime).start();
      this.channel =
          NettyChannelBuilder.forAddress("127.0.0.1", this.server.port()).usePlaintext().build();
      this.catalog =
          CatalogServiceGrpc.newBlockingStub(this.channel).withDeadlineAfter(45, TimeUnit.SECONDS);
      this.docs =
          DocumentServiceGrpc.newBlockingStub(this.channel).withDeadlineAfter(45, TimeUnit.SECONDS);
      this.queries =
          QueryServiceGrpc.newBlockingStub(this.channel).withDeadlineAfter(45, TimeUnit.SECONDS);
    }

    private String create(String name) throws Exception {
      String id =
          this.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName(name)
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(bson("{mappings:{dynamic:true}}"))
                      .build())
              .getIndexId();
      ready(id, 1);
      return id;
    }

    private IndexDescription ready(String id, long revision) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        var index = this.catalog.getIndex(GetIndexRequest.newBuilder().setIndexId(id).build());
        if (index.getLifecycleState().equals("READY") && index.getActiveRevision() == revision) {
          return index;
        }
        Thread.sleep(20);
      }
      throw new AssertionError(
          "Index never became ready: "
              + this.catalog.getIndex(GetIndexRequest.newBuilder().setIndexId(id).build()));
    }

    private void upsert(String id, String json) {
      assertThat(
              this.docs
                  .upsert(
                      UpsertRequest.newBuilder()
                          .setIndexId(id)
                          .addDocumentsBson(bson(json))
                          .build())
                  .getIndexedCount())
          .isEqualTo(1);
    }

    private IndexStats stats(String id) {
      return this.catalog.getIndexStats(
          GetIndexStatsRequest.newBuilder().setIndexId(id).setRefresh(true).build());
    }

    private IndexFieldStatsResponse fieldStats(String id, int size) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (System.nanoTime() < deadline) {
        var result =
            this.catalog.getIndexFieldStats(
                GetIndexFieldStatsRequest.newBuilder().setIndexId(id).setPageSize(size).build());
        if (result.getState() == StatsCollectionState.STATS_COLLECTION_STATE_READY) {
          return result;
        }
        Thread.sleep(20);
      }
      throw new AssertionError("Field statistics did not finish");
    }

    private BsonDocument results(String id, String term) {
      return query(id, "{text:{path:'title',query:'" + term + "'}}");
    }

    private BsonDocument query(String id, String json) {
      return new RawBsonDocument(
          this.queries
              .search(QueryRequest.newBuilder().setIndexId(id).setQueryBson(bson(json)).build())
              .getBatchBson()
              .toByteArray());
    }

    @Override
    public void close() throws Exception {
      this.channel.shutdownNow();
      this.channel.awaitTermination(5, TimeUnit.SECONDS);
      try {
        this.server.close();
      } finally {
        this.runtime.close();
      }
    }
  }
}
