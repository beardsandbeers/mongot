package com.xgen.mongot.standalone.server;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.cursor.CursorQuery;
import com.xgen.mongot.cursor.batch.BatchCursorOptions;
import com.xgen.mongot.cursor.batch.QueryCursorOptions;
import com.xgen.mongot.index.query.OperatorQuery;
import com.xgen.mongot.index.query.QueryOptimizationFlags;
import com.xgen.mongot.index.query.SearchQuery;
import com.xgen.mongot.index.query.operators.CompoundClause;
import com.xgen.mongot.index.query.operators.CompoundOperator;
import com.xgen.mongot.index.query.operators.JoinOperator;
import com.xgen.mongot.index.query.operators.MaterializedJoinOperator;
import com.xgen.mongot.standalone.catalog.LocalIndexCatalog;
import com.xgen.mongot.standalone.catalog.StandaloneDefinitionValidator;
import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.grpc.CatalogServiceGrpc;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DeleteRequest;
import com.xgen.mongot.standalone.grpc.DocumentServiceGrpc;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.GetIndexRequest;
import com.xgen.mongot.standalone.grpc.GetMoreRequest;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.KillCursorRequest;
import com.xgen.mongot.standalone.grpc.QueryRequest;
import com.xgen.mongot.standalone.grpc.QueryResponse;
import com.xgen.mongot.standalone.grpc.QueryServiceGrpc;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.grpc.UpsertRequest;
import com.xgen.mongot.standalone.runtime.StandaloneRuntime;
import com.xgen.mongot.util.Bytes;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Exercises actual Lucene indexes and the public gRPC entry point, including cursor dependencies.
 */
public class StandaloneJoinsTest {
  private static final String MAPPING =
      """
      {numPartitions:2,storedSource:true,mappings:{dynamic:false,fields:{
        key:{type:'token'}, parent:{type:'token'}, office:{type:'token'},
        title:{type:'string'}
      }}}
      """;
  private static final String JOIN =
      """
      {compound:{filter:[{join:{from:'foreign',localField:'parent',foreignField:'key',
        operator:{equals:{path:'office',value:'Berlin'}}}}]},returnStoredSource:true,
        count:{type:'total'}}
      """;

  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void search_guideExampleWithDifferentMappings_returnsOnlyMatchingArchitect()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String accounts =
          f.create(
              "accounts_search",
              """
              {numPartitions:2,mappings:{dynamic:false,fields:{
                accountKey:{type:'token'},region:{type:'token'},industry:{type:'token'}
              }}}
              """);
      String contacts =
          f.create(
              "contacts_search",
              """
              {numPartitions:2,storedSource:true,mappings:{dynamic:false,fields:{
                parent:{type:'token'},title:{type:'string'}
              }}}
              """);
      f.upsert(accounts, "{_id:'a1',accountKey:'a1',region:'bay area',industry:'aerospace'}");
      f.upsert(accounts, "{_id:'a2',accountKey:'a2',region:'new york',industry:'aerospace'}");
      f.upsert(accounts, "{_id:'a3',accountKey:'a3',region:'bay area',industry:'finance'}");
      f.upsert(contacts, "{_id:'c1',parent:'a1',title:'Principal Software Architect'}");
      f.upsert(contacts, "{_id:'c2',parent:'a2',title:'Solutions Architect'}");
      f.upsert(contacts, "{_id:'c3',parent:'a1',title:'Aerospace Engineer'}");
      f.upsert(contacts, "{_id:'c4',parent:'a3',title:'Cloud Architect'}");

      var response =
          f.search(
              contacts,
              """
              {compound:{must:[{text:{path:'title',query:'architect'}}],filter:[{
                join:{from:'accounts_search',localField:'parent',foreignField:'accountKey',
                  operator:{compound:{filter:[
                    {equals:{path:'region',value:'bay area'}},
                    {equals:{path:'industry',value:'aerospace'}}
                  ]}}}
              }]},returnStoredSource:true}
              """,
              100);

      assertThat(ids(response)).containsExactly("c1");
    }
  }

  @Test
  public void search_crossIndexArraysAndDuplicates_filtersBeforeCountWithoutDuplicatingHits()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'p1',key:['a','b'],office:'Berlin'}");
      f.upsert(foreign, "{_id:'p2',key:'a',office:'Berlin'}");
      f.upsert(foreign, "{_id:'p3',key:'c',office:'Paris'}");
      f.upsert(local, "{_id:'a',parent:'a',title:'architect'}");
      f.upsert(local, "{_id:'b',parent:['b','a'],title:'architect'}");
      f.upsert(local, "{_id:'c',parent:'c',title:'architect'}");
      f.upsert(local, "{_id:'missing',title:'architect'}");
      f.upsert(local, "{_id:'null',parent:null,title:'architect'}");

      var response = f.search(local, JOIN, 100);

      assertThat(ids(response)).containsExactly("a", "b");
      assertThat(
              new RawBsonDocument(response.getMetadataBson().toByteArray())
                  .getDocument("count")
                  .getNumber("total")
                  .longValue())
          .isEqualTo(2);
      assertThat(response.getCursorId()).isEqualTo(0);
    }
  }

  @Test
  public void search_selfJoinOmittedOrExplicitFrom_reusesPartitionWideMembership()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String index = f.create("employees", MAPPING);
      f.upsert(index, "{_id:'manager',key:'m',office:'Berlin'}");
      f.upsert(index, "{_id:'employee',parent:'m'}");
      f.upsert(index, "{_id:'self',key:'s',parent:'s',office:'Berlin'}");
      f.upsert(index, "{_id:'orphan',parent:'absent'}");
      String query = JOIN.replace("from:'foreign',", "");

      var implicit = f.search(index, query, 100);
      var explicit = f.search(index, JOIN.replace("'foreign'", "'employees'"), 100);

      assertThat(ids(implicit)).containsExactly("employee", "self");
      assertThat(ids(explicit)).containsExactlyElementsIn(ids(implicit));
    }
  }

  @Test
  public void search_exclusion_preservesMissingAndOrphanKeys() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'p',key:'a',office:'Berlin'}");
      f.upsert(local, "{_id:'matched',parent:'a'}");
      f.upsert(local, "{_id:'orphan',parent:'absent'}");
      f.upsert(local, "{_id:'missing'}");

      var response = f.search(local, JOIN.replace("filter:", "mustNot:"), 100);

      assertThat(ids(response)).containsExactly("orphan", "missing");
    }
  }

  @Test
  public void getMore_foreignDocumentChanges_keepsInitialMembership() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'p',key:'a',office:'Berlin'}");
      f.upsert(local, "{_id:'first',parent:'a'}");
      f.upsert(local, "{_id:'second',parent:'a'}");
      var first = f.search(local, JOIN, 1);
      assertThat(first.getCursorId()).isNotEqualTo(0);

      f.docs.delete(
          DeleteRequest.newBuilder().setIndexId(foreign).addIdsBson(bson("{_id:'p'}")).build());
      var second =
          f.queries.getMore(GetMoreRequest.newBuilder().setCursorId(first.getCursorId()).build());
      var fresh = f.search(local, JOIN, 100);

      assertThat(ids(second)).hasSize(1);
      assertThat(ids(second)).containsNoneIn(ids(first));
      assertThat(ids(fresh)).isEmpty();
    }
  }

  @Test
  public void getMore_droppedForeignIndexAndReusedName_invalidatesCursor() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'p',key:'a',office:'Berlin'}");
      f.upsert(local, "{_id:'first',parent:'a'}");
      f.upsert(local, "{_id:'second',parent:'a'}");
      var first = f.search(local, JOIN, 1);

      f.catalog.dropIndex(
          DropIndexRequest.newBuilder().setIndexId(foreign).setExpectedRevision(1).build());
      String replacement = f.create("foreign", MAPPING);
      var failure =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  f.queries.getMore(
                      GetMoreRequest.newBuilder().setCursorId(first.getCursorId()).build()));

      assertThat(replacement).isNotEqualTo(foreign);
      assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
      assertThat(ids(f.search(local, JOIN, 100))).isEmpty();
    }
  }

  @Test
  public void getMore_foreignMappingUpdate_invalidatesCursorAndUsesReingestedContent()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'p',key:'a',office:'Berlin'}");
      f.upsert(local, "{_id:'first',parent:'a'}");
      f.upsert(local, "{_id:'second',parent:'a'}");
      var first = f.search(local, JOIN, 1);

      f.catalog.updateIndex(
          UpdateIndexRequest.newBuilder()
              .setIndexId(foreign)
              .setExpectedRevision(1)
              .setDefinitionBson(bson(MAPPING))
              .build());
      f.ready(foreign, 2);
      var failure =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  f.queries.getMore(
                      GetMoreRequest.newBuilder().setCursorId(first.getCursorId()).build()));

      assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
      assertThat(ids(f.search(local, JOIN, 100))).isEmpty();
      f.upsert(foreign, "{_id:'p',key:'a',office:'Berlin'}");
      assertThat(ids(f.search(local, JOIN, 100))).containsExactly("first", "second");
    }
  }

  @Test
  public void search_foreignNameAndMappingValidation_returnsSpecificErrors() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);

      var missing =
          assertThrows(
              StatusRuntimeException.class,
              () -> f.search(local, JOIN.replace("'foreign'", "'Foreign'"), 100));
      var mapping =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  f.search(local, JOIN.replace("foreignField:'key'", "foreignField:'title'"), 100));
      var scoring =
          assertThrows(
              StatusRuntimeException.class,
              () -> f.search(local, JOIN.replace("filter:", "must:"), 100));

      assertThat(missing.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
      assertThat(mapping.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(scoring.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  public void search_localTextAndSort_preservesScoresAndFiltersBeforeTopK() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'p',key:'a',office:'Berlin'}");
      f.upsert(local, "{_id:'a',key:'a',parent:'a',title:'architect'}");
      f.upsert(local, "{_id:'b',key:'b',parent:'a',title:'principal software architect'}");
      f.upsert(local, "{_id:'excluded',key:'z',parent:'missing',title:'architect'}");
      var baseline =
          f.search(local, "{text:{path:'title',query:'architect'},returnStoredSource:true}", 100);
      var query = BsonDocument.parse(JOIN);
      query
          .getDocument("compound")
          .put("must", org.bson.BsonArray.parse("[{text:{path:'title',query:'architect'}}]"));
      query.put("sort", BsonDocument.parse("{key:1}"));
      query.put("highlight", BsonDocument.parse("{path:'title'}"));
      query.put("scoreDetails", org.bson.BsonBoolean.TRUE);

      var filtered = f.search(local, query.toJson(), 1);
      var next =
          f.queries.getMore(
              GetMoreRequest.newBuilder().setCursorId(filtered.getCursorId()).build());

      assertThat(ids(filtered)).containsExactly("a");
      assertThat(ids(next)).containsExactly("b");
      assertThat(score(filtered, "a")).isEqualTo(score(baseline, "a"));
      assertThat(score(next, "b")).isEqualTo(score(baseline, "b"));
      assertThat(
              new RawBsonDocument(filtered.getBatchBson().toByteArray())
                  .getArray("results")
                  .getFirst()
                  .asDocument()
                  .containsKey("$searchHighlights"))
          .isTrue();
    }
  }

  @Test
  public void search_facetCollector_appliesJoinToHitsAndBucketsAcrossPartitions() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'p',key:'a',office:'Berlin'}");
      f.upsert(local, "{_id:'first',parent:'a',office:'Berlin'}");
      f.upsert(local, "{_id:'second',parent:'a',office:'Paris'}");
      f.upsert(local, "{_id:'excluded',parent:'missing',office:'Rome'}");
      var query =
          new BsonDocument(
                  "facet",
                  new BsonDocument(
                          "operator",
                          new BsonDocument(
                              "compound", BsonDocument.parse(JOIN).getDocument("compound")))
                      .append(
                          "facets", BsonDocument.parse("{offices:{type:'string',path:'office'}}")))
              .append("returnStoredSource", org.bson.BsonBoolean.TRUE);

      var response = f.search(local, query.toJson(), 100);
      var metadata = new RawBsonDocument(response.getMetadataBson().toByteArray());
      query
          .getDocument("facet")
          .getDocument("operator")
          .getDocument("compound")
          .getArray("filter")
          .add(
              BsonDocument.parse(
                  "{equals:{path:'office',value:'Berlin',doesNotAffect:'offices'}}"));
      var drillSideways = f.search(local, query.toJson(), 100);
      var drillMetadata = new RawBsonDocument(drillSideways.getMetadataBson().toByteArray());

      assertThat(ids(response)).containsExactly("first", "second");
      assertThat(
              metadata.getDocument("facet").getDocument("offices").getArray("buckets").stream()
                  .map(value -> value.asDocument().getString("_id").getValue())
                  .toList())
          .containsExactly("Berlin", "Paris");
      assertThat(ids(drillSideways)).containsExactly("first");
      // Standalone defaults disable drill-sideways; preserve the existing facet behavior.
      assertThat(
              drillMetadata.getDocument("facet").getDocument("offices").getArray("buckets").stream()
                  .map(value -> value.asDocument().getString("_id").getValue())
                  .toList())
          .containsExactly("Berlin");
    }
  }

  @Test
  public void search_multipleJoinsAndBooleanFilterComposition_intersectsAndUnionsMembership()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String another = f.create("another", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'berlin',key:['a','b'],office:'Berlin'}");
      f.upsert(another, "{_id:'paris',key:'c',office:'Paris'}");
      f.upsert(local, "{_id:'both',parent:'a',key:'c'}");
      f.upsert(local, "{_id:'berlin-only',parent:'b',key:'a'}");
      f.upsert(local, "{_id:'paris-only',parent:'missing',key:'c'}");
      var intersection = BsonDocument.parse(JOIN);
      intersection
          .getDocument("compound")
          .getArray("filter")
          .add(
              BsonDocument.parse(
                  """
                  {join:{from:'another',localField:'key',foreignField:'key',
                            operator:{equals:{path:'office',value:'Paris'}}}}
                  """));
      var union = BsonDocument.parse(JOIN);
      var firstJoin =
          BsonDocument.parse(JOIN).getDocument("compound").getArray("filter").getFirst();
      union
          .getDocument("compound")
          .put(
              "filter",
              org.bson.BsonArray.parse(
                  """
                  [{compound:{should:[{equals:{path:'key',value:'c'}}],minimumShouldMatch:1}}]
                  """));
      union
          .getDocument("compound")
          .getArray("filter")
          .getFirst()
          .asDocument()
          .getDocument("compound")
          .getArray("should")
          .add(firstJoin);

      var both = f.search(local, intersection.toJson(), 100);
      var either = f.search(local, union.toJson(), 100);

      assertThat(ids(both)).containsExactly("both");
      assertThat(ids(either)).containsExactly("both", "berlin-only", "paris-only");
    }
  }

  @Test
  public void search_reverseJoin_returnsEachForeignDocumentOnce() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'a',key:'a'}");
      f.upsert(foreign, "{_id:'b',key:'b'}");
      f.upsert(local, "{_id:'one',parent:'a',title:'architect'}");
      f.upsert(local, "{_id:'two',parent:'a',title:'architect'}");
      f.upsert(local, "{_id:'three',parent:'b',title:'engineer'}");

      var response =
          f.search(
              foreign,
              """
              {compound:{filter:[{join:{from:'local',localField:'key',foreignField:'parent',
                operator:{text:{path:'title',query:'architect'}}}}]},returnStoredSource:true}
              """,
              100);

      assertThat(ids(response)).containsExactly("a");
    }
  }

  @Test
  public void search_emptyAndWrongTypeKeys_usesIndexedTokenEquality() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'p',key:'',office:'Berlin'}");
      f.upsert(local, "{_id:'empty',parent:''}");
      f.upsert(local, "{_id:'array',parent:[]}");
      f.upsert(local, "{_id:'number',parent:0}");
      f.upsert(local, "{_id:'null',parent:null}");

      var response = f.search(local, JOIN, 100);

      assertThat(ids(response)).containsExactly("empty");
    }
  }

  @Test
  public void search_normalizers_comparesEffectiveNormalizerAndRejectsMismatch() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local =
          f.create(
              "local",
              MAPPING.replace("parent:{type:'token'}", "parent:{type:'token',normalizer:'none'}"));
      String mismatch =
          f.create(
              "mismatch",
              MAPPING.replace(
                  "parent:{type:'token'}", "parent:{type:'token',normalizer:'lowercase'}"));
      f.upsert(foreign, "{_id:'p',key:'ABC',office:'Berlin'}");
      f.upsert(local, "{_id:'matched',parent:'ABC'}");

      var response = f.search(local, JOIN, 100);
      var error = assertThrows(StatusRuntimeException.class, () -> f.search(mismatch, JOIN, 100));

      assertThat(ids(response)).containsExactly("matched");
      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  public void search_matchingLowercaseNormalizers_comparesNormalizedUnicodeTokens()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign =
          f.create(
              "foreign",
              MAPPING.replace("key:{type:'token'}", "key:{type:'token',normalizer:'lowercase'}"));
      String local =
          f.create(
              "local",
              MAPPING.replace(
                  "parent:{type:'token'}", "parent:{type:'token',normalizer:'lowercase'}"));
      f.upsert(foreign, "{_id:'p',key:'ÄBC',office:'Berlin'}");
      f.upsert(local, "{_id:'matched',parent:'äbc'}");

      var response = f.search(local, JOIN, 100);

      assertThat(ids(response)).containsExactly("matched");
    }
  }

  @Test
  public void search_nestedJoinAndJoinCountLimit_rejectsWithoutPartialResults() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      var root = BsonDocument.parse(JOIN);
      var join = root.getDocument("compound").getArray("filter").get(0).asDocument();
      var nested = BsonDocument.parse(JOIN);
      nested
          .getDocument("compound")
          .getArray("filter")
          .get(0)
          .asDocument()
          .getDocument("join")
          .put("operator", new BsonDocument("compound", root.getDocument("compound")));
      root.getDocument("compound")
          .put(
              "filter",
              new org.bson.BsonArray(
                  java.util.Collections.nCopies(StandaloneJoinPlanner.MAX_JOINS + 1, join)));

      var nestedError =
          assertThrows(StatusRuntimeException.class, () -> f.search(local, nested.toJson(), 100));
      var limitError =
          assertThrows(StatusRuntimeException.class, () -> f.search(local, root.toJson(), 100));

      assertThat(nestedError.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(limitError.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }
  }

  @Test
  public void search_repeatedMemoryBudgetFailures_releasesReservationsForLaterQueries()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, keyDocument(120, 7000));

      assertBudgetFailures(f, local);
      f.upsert(foreign, keyDocument(500, 64));
      f.upsert(local, "{_id:'match',parent:'" + "x".repeat(64) + "0'}");

      assertThat(ids(f.search(local, JOIN, 100))).containsExactly("match");
    }
  }

  private static String keyDocument(int count, int length) {
    var values =
        java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> (org.bson.BsonValue) new org.bson.BsonString("x".repeat(length) + i))
            .toList();
    return new BsonDocument("_id", new org.bson.BsonString("p"))
        .append("office", new org.bson.BsonString("Berlin"))
        .append("key", new org.bson.BsonArray(values))
        .toJson();
  }

  private static void assertBudgetFailures(Fixture f, String local) {
    for (int attempt = 0; attempt < 10; attempt++) {
      var error = assertThrows(StatusRuntimeException.class, () -> f.search(local, JOIN, 100));
      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }
  }

  private static double score(QueryResponse response, String id) {
    return new RawBsonDocument(response.getBatchBson().toByteArray())
        .getArray("results").stream()
            .map(org.bson.BsonValue::asDocument)
            .filter(hit -> hit.getDocument("storedSource").getString("_id").getValue().equals(id))
            .findFirst()
            .orElseThrow()
            .getNumber("$searchScore")
            .doubleValue();
  }

  @Test
  public void killCursor_joinMembership_releasesRetainedMemory() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, "{_id:'p',key:'a',office:'Berlin'}");
      f.upsert(local, "{_id:'first',parent:'a'}");
      f.upsert(local, "{_id:'second',parent:'a'}");
      var first = f.search(local, JOIN, 1);
      assertThat(reservedBytes(f)).isGreaterThan(0.0);

      f.queries.killCursor(KillCursorRequest.newBuilder().setCursorId(first.getCursorId()).build());

      assertThat(reservedBytes(f)).isEqualTo(0.0);
      assertThat(ids(f.search(local, JOIN, 100))).containsExactly("first", "second");
      assertThat(reservedBytes(f)).isEqualTo(0.0);
    }
  }

  private static double reservedBytes(Fixture f) throws Exception {
    return f.runtime.serviceStatus().getMetricsList().stream()
        .filter(metric -> metric.getName().equals("standalone.joins.reservedBytes"))
        .flatMap(metric -> metric.getMeasurementsMap().values().stream())
        .mapToDouble(Double::doubleValue)
        .sum();
  }

  @Test
  public void search_queryOnlyIndexGrants_authorizesForeignAndRechecksContinuation()
      throws Exception {
    String local;
    String foreign;
    try (var seed = new Fixture(data(), List.of())) {
      foreign = seed.create("foreign", MAPPING);
      local = seed.create("local", MAPPING);
      seed.upsert(foreign, "{_id:'p',key:'a',office:'Berlin'}");
      seed.upsert(local, "{_id:'first',parent:'a'}");
      seed.upsert(local, "{_id:'second',parent:'a'}");
    }
    var onlyLocal =
        new StandaloneConfig.Principal(
            "reader", digest("local"), Set.of(StandaloneConfig.Role.QUERY), Set.of(local));
    var both =
        new StandaloneConfig.Principal(
            "reader", digest("both"), Set.of(StandaloneConfig.Role.QUERY), Set.of(local, foreign));
    try (var f = new Fixture(data(), List.of(onlyLocal, both))) {
      f.readyInternally(local, 1);
      f.readyInternally(foreign, 1);
      var restricted = f.caller("local");
      var authorized = f.caller("both");
      var first = authorized.search(request(local, JOIN, 1));

      var deniedSearch =
          assertThrows(
              StatusRuntimeException.class, () -> restricted.search(request(local, JOIN, 100)));
      var deniedPage =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  restricted.getMore(
                      GetMoreRequest.newBuilder().setCursorId(first.getCursorId()).build()));
      var closed =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  authorized.getMore(
                      GetMoreRequest.newBuilder().setCursorId(first.getCursorId()).build()));

      assertThat(ids(first)).hasSize(1);
      assertThat(deniedSearch.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
      assertThat(deniedPage.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
      assertThat(closed.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }
  }

  @Test
  public void search_pinnedSelfJoinSnapshot_survivesRefreshBetweenKeyCollectionAndCursorCreation()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String index = f.create("employees", MAPPING);
      f.upsert(index, "{_id:'manager',key:'m',office:'Berlin'}");
      f.upsert(index, "{_id:'employee',parent:'m'}");
      var generation =
          f.runtime
              .liveIndexes()
              .getIndexById(new org.bson.types.ObjectId(index))
              .orElseThrow()
              .getGenerationId();
      var parsed =
          (OperatorQuery)
              SearchQuery.fromBson(
                  BsonDocument.parse(
                      JOIN.replace("from:'foreign',", "")
                          .replace(
                              "returnStoredSource:true",
                              "index:'employees',returnStoredSource:true")));
      var compound = (CompoundOperator) parsed.operator();
      var join = (JoinOperator) compound.filter().operators().getFirst();
      try (var snapshot = f.runtime.acquireQuerySnapshot(generation, false)) {
        var keys = new HashSet<String>();
        snapshot.collectKeys(join.operator(), join.foreignField(), keys::add, () -> {});
        f.upsert(index, "{_id:'manager',key:'m',office:'Paris'}");
        f.upsert(index, "{_id:'employee',parent:'absent'}");
        var materialized = new MaterializedJoinOperator(join, List.copyOf(keys));
        var planned =
            parsed.withOperator(
                new CompoundOperator(
                    compound.score(),
                    new CompoundClause(List.of(materialized)),
                    compound.must(),
                    compound.mustNot(),
                    compound.should(),
                    compound.minimumShouldMatch(),
                    compound.doesNotAffect()));

        var cursor =
            f.runtime
                .cursors()
                .newCursor(
                    generation,
                    new CursorQuery.Search(planned, Optional.of(snapshot)),
                    new QueryCursorOptions(Optional.empty(), Optional.of(100), false),
                    QueryOptimizationFlags.DEFAULT_OPTIONS);
        try {
          var batch =
              f.runtime
                  .cursors()
                  .getNextBatch(
                      cursor.cursorId,
                      Bytes.ofBytes(1024 * 1024),
                      new BatchCursorOptions(Optional.empty(), Optional.of(100)));

          assertThat(keys).containsExactly("m");
          assertThat(batch.batch.asArray()).hasSize(1);
          assertThat(
                  batch
                      .batch
                      .asArray()
                      .getFirst()
                      .asDocument()
                      .getDocument("storedSource")
                      .getString("_id")
                      .getValue())
              .isEqualTo("employee");
          assertThat(ids(f.search(index, JOIN.replace("from:'foreign',", ""), 100))).isEmpty();
        } finally {
          f.runtime.cursors().killCursor(cursor.cursorId);
        }
      }
    }
  }

  @Test
  public void collectKeys_cancellationDuringCollection_releasesSnapshotsForFreshQueries()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      f.upsert(foreign, keyDocument(20, 8));
      f.upsert(foreign, "{_id:'second',key:'other',office:'Berlin'}");
      f.upsert(foreign, "{_id:'third',key:'another',office:'Berlin'}");
      f.upsert(local, "{_id:'match',parent:'xxxxxxxx0'}");
      var generation =
          f.runtime
              .liveIndexes()
              .getIndexById(new org.bson.types.ObjectId(foreign))
              .orElseThrow()
              .getGenerationId();
      var parsed = (OperatorQuery) SearchQuery.fromBson(BsonDocument.parse(JOIN));
      var join =
          (JoinOperator) ((CompoundOperator) parsed.operator()).filter().operators().getFirst();
      var work = new AtomicInteger();
      try (var snapshot = f.runtime.acquireQuerySnapshot(generation, false)) {
        assertThrows(
            CancellationException.class,
            () ->
                snapshot.collectKeys(
                    join.operator(),
                    join.foreignField(),
                    ignored -> {},
                    () -> cancelAfter(work, 4)));
      }

      assertThat(ids(f.search(local, JOIN, 100))).containsExactly("match");
    }
  }

  @Test
  public void search_overlongKeys_followsDocumentedIndexedTokenTruncation() throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      String foreign = f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      String prefix = "x".repeat(8191);
      f.upsert(foreign, "{_id:'p',key:'" + prefix + "a',office:'Berlin'}");
      f.upsert(local, "{_id:'match',parent:'" + prefix + "b'}");

      var response = f.search(local, JOIN, 100);

      assertThat(ids(response)).containsExactly("match");
    }
  }

  private static void cancelAfter(AtomicInteger work, int limit) {
    if (work.incrementAndGet() == limit) {
      throw new CancellationException("Cancelled during foreign collection");
    }
  }

  @Test
  public void search_vectorTargetAndEmbeddedForeignPredicate_rejectsUnsupportedForms()
      throws Exception {
    try (var f = new Fixture(data(), List.of())) {
      f.create("foreign", MAPPING);
      String local = f.create("local", MAPPING);
      String vector =
          f.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName("vectors")
                      .setType(IndexType.INDEX_TYPE_VECTOR_SEARCH)
                      .setDefinitionBson(
                          bson(
                              """
                              {fields:[{type:'vector',path:'embedding',numDimensions:3,
                                similarity:'cosine'}]}
                              """))
                      .build())
              .getIndexId();
      f.ready(vector, 1);
      var embedded = BsonDocument.parse(JOIN);
      embedded
          .getDocument("compound")
          .getArray("filter")
          .getFirst()
          .asDocument()
          .getDocument("join")
          .put(
              "operator",
              BsonDocument.parse(
                  """
                  {hasRoot:{operator:{equals:{path:'office',value:'Berlin'}}}}
                  """));

      var wrongType =
          assertThrows(
              StatusRuntimeException.class,
              () -> f.search(local, JOIN.replace("'foreign'", "'vectors'"), 100));
      var wrongScope =
          assertThrows(StatusRuntimeException.class, () -> f.search(local, embedded.toJson(), 100));

      assertThat(wrongType.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(wrongScope.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  public void checkCancellation_expiredDeadline_preservesDeadlineStatus() throws Exception {
    var executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    try (var context = io.grpc.Context.ROOT.withDeadlineAfter(-1, TimeUnit.SECONDS, executor)) {
      var error =
          assertThrows(
              StatusRuntimeException.class, () -> StandaloneJoinPlanner.checkCancellation(context));

      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    } finally {
      executor.shutdownNow();
    }
  }

  private Path data() {
    return this.folder.getRoot().toPath().resolve("data");
  }

  private static byte[] digest(String value) {
    return LocalIndexCatalog.digest(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static ByteString bson(String json) {
    return ByteString.copyFrom(StandaloneDefinitionValidator.encode(BsonDocument.parse(json)));
  }

  private static QueryRequest request(String id, String query, int size) {
    return QueryRequest.newBuilder()
        .setIndexId(id)
        .setQueryBson(bson(query))
        .setBatchSize(size)
        .build();
  }

  private static List<String> ids(QueryResponse response) {
    return new RawBsonDocument(response.getBatchBson().toByteArray())
        .getArray("results").stream()
            .map(
                value -> value.asDocument().getDocument("storedSource").getString("_id").getValue())
            .toList();
  }

  private static final class Fixture implements AutoCloseable {
    private final StandaloneRuntime runtime;
    private final StandaloneGrpcServer server;
    private final ManagedChannel channel;
    private final CatalogServiceGrpc.CatalogServiceBlockingStub catalog;
    private final DocumentServiceGrpc.DocumentServiceBlockingStub docs;
    private final QueryServiceGrpc.QueryServiceBlockingStub queries;

    private Fixture(Path data, List<StandaloneConfig.Principal> principals) throws Exception {
      var base = StandaloneConfig.development(data, 0);
      var config =
          new StandaloneConfig(
              base.dataPath(),
              base.address(),
              base.documentLimits(),
              base.maxInFlightRequests(),
              base.maxInFlightBytes(),
              base.minFreeDiskBytes(),
              base.commitInterval(),
              base.refreshInterval(),
              base.maxRpcDuration(),
              base.tls(),
              principals);
      this.runtime = new StandaloneRuntime(config);
      this.server = new StandaloneGrpcServer(this.runtime).start();
      this.channel =
          NettyChannelBuilder.forAddress("127.0.0.1", this.server.port()).usePlaintext().build();
      this.catalog = CatalogServiceGrpc.newBlockingStub(this.channel);
      this.docs = DocumentServiceGrpc.newBlockingStub(this.channel);
      this.queries = QueryServiceGrpc.newBlockingStub(this.channel);
    }

    private QueryServiceGrpc.QueryServiceBlockingStub caller(String token) {
      var metadata = new Metadata();
      metadata.put(
          Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
      return this.queries.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private String create(String name, String mapping) throws Exception {
      String id =
          this.catalog
              .createIndex(
                  CreateIndexRequest.newBuilder()
                      .setName(name)
                      .setType(IndexType.INDEX_TYPE_SEARCH)
                      .setDefinitionBson(bson(mapping))
                      .build())
              .getIndexId();
      ready(id, 1);
      return id;
    }

    private IndexDescription ready(String id, long revision) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        var result = this.catalog.getIndex(GetIndexRequest.newBuilder().setIndexId(id).build());
        if (result.getLifecycleState().equals("READY") && result.getActiveRevision() == revision) {
          return result;
        }
        Thread.sleep(20);
      }
      throw new AssertionError("Index did not become ready");
    }

    private void readyInternally(String id, long revision) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        var result =
            this.runtime.serviceStatus().getIndexesList().stream()
                .filter(index -> index.getIndexId().equals(id))
                .findFirst()
                .orElseThrow();
        if (result.getLifecycleState().equals("READY") && result.getActiveRevision() == revision) {
          return;
        }
        Thread.sleep(20);
      }
      throw new AssertionError("Index did not become ready");
    }

    private void upsert(String id, String doc) {
      this.docs.upsert(
          UpsertRequest.newBuilder().setIndexId(id).addDocumentsBson(bson(doc)).build());
    }

    private QueryResponse search(String id, String query, int size) {
      return this.queries.search(request(id, query, size));
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
