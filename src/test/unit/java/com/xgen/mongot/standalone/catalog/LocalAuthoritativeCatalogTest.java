package com.xgen.mongot.standalone.catalog;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.standalone.grpc.CreateCollectionRequest;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.ListIndexesRequest;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.store.StandaloneStore;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.bson.BsonDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalAuthoritativeCatalogTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void createIndex_equivalentDefinition_returnsExistingIdentity() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var catalog = new LocalAuthoritativeCatalog(store);
      var collection = catalog.createCollection("user", collectionRequest());
      var original =
          catalog.createIndex("user", indexRequest(collection.getCollectionId(), "first"));

      var equivalent =
          catalog.createIndex(
              "user",
              indexRequest(collection.getCollectionId(), "second").toBuilder()
                  .setDefinitionBson(bson("{numPartitions: 1, mappings: {dynamic: true}}"))
                  .build());

      assertThat(equivalent.getIndexId()).isEqualTo(original.getIndexId());
      assertThat(
              catalog
                  .listIndexes(
                      ListIndexesRequest.newBuilder()
                          .setCollectionId(collection.getCollectionId())
                          .build())
                  .getIndexesCount())
          .isEqualTo(1);
    }
  }

  @Test
  public void createCollection_sameRequestIdDifferentContent_rejects() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var catalog = new LocalAuthoritativeCatalog(store);
      catalog.createCollection("user", collectionRequest());

      var error =
          assertThrows(
              StatusRuntimeException.class,
              () ->
                  catalog.createCollection(
                      "user", collectionRequest().toBuilder().setName("different").build()));

      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  public void restart_afterLaterUpdate_replaysOriginalManagementOutcome() throws Exception {
    String collectionId;
    String indexId;
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var catalog = new LocalAuthoritativeCatalog(store);
      collectionId = catalog.createCollection("user", collectionRequest()).getCollectionId();
      indexId = catalog.createIndex("user", indexRequest(collectionId, "create")).getIndexId();
      catalog.updateIndex(
          "user",
          UpdateIndexRequest.newBuilder()
              .setRequestId("update")
              .setIndexId(indexId)
              .setExpectedRevision(1)
              .setDefinitionBson(bson("{mappings: {dynamic: true}, storedSource: true}"))
              .build());
    }

    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var catalog = new LocalAuthoritativeCatalog(store);
      var retried = catalog.createIndex("user", indexRequest(collectionId, "create"));

      assertThat(catalog.createCollection("user", collectionRequest()).getCollectionId())
          .isEqualTo(collectionId);
      assertThat(retried.getIndexId()).isEqualTo(indexId);
      assertThat(retried.getDesiredRevision()).isEqualTo(1);
      assertThat(catalog.getIndex(indexId).getDesiredRevision()).isEqualTo(2);
    }
  }

  @Test
  public void updateIndex_concurrentExpectedRevision_acceptsExactlyOne() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath());
        var executor = Executors.newFixedThreadPool(2)) {
      var catalog = new LocalAuthoritativeCatalog(store);
      var collection = catalog.createCollection("user", collectionRequest());
      var index = catalog.createIndex("user", indexRequest(collection.getCollectionId(), "create"));

      var outcomes =
          executor.invokeAll(
              List.<Callable<Status.Code>>of(
                  () -> update(catalog, index.getIndexId(), "first"),
                  () -> update(catalog, index.getIndexId(), "second")));

      assertThat(List.of(outcomes.get(0).get(), outcomes.get(1).get()))
          .containsExactly(Status.Code.OK, Status.Code.ABORTED);
      assertThat(catalog.getIndex(index.getIndexId()).getDesiredRevision()).isEqualTo(2);
    }
  }

  @Test
  public void updateIndex_rejectedRequestAfterStateChanges_remainsRejected() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var catalog = new LocalAuthoritativeCatalog(store);
      var collection = catalog.createCollection("user", collectionRequest());
      var index = catalog.createIndex("user", indexRequest(collection.getCollectionId(), "create"));
      var ahead =
          UpdateIndexRequest.newBuilder()
              .setRequestId("ahead")
              .setIndexId(index.getIndexId())
              .setExpectedRevision(2)
              .setDefinitionBson(bson("{mappings: {dynamic: true}}"))
              .build();
      assertThrows(StatusRuntimeException.class, () -> catalog.updateIndex("user", ahead));
      assertThat(update(catalog, index.getIndexId(), "advance")).isEqualTo(Status.Code.OK);

      var retried =
          assertThrows(StatusRuntimeException.class, () -> catalog.updateIndex("user", ahead));

      assertThat(retried.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
      assertThat(catalog.getIndex(index.getIndexId()).getDesiredRevision()).isEqualTo(2);
    }
  }

  @Test
  public void dropAndRecreateIndex_allocatesNewIdentityAndRetainsRetryTombstone() throws Exception {
    try (var store = new StandaloneStore(this.folder.getRoot().toPath())) {
      var catalog = new LocalAuthoritativeCatalog(store);
      var collection = catalog.createCollection("user", collectionRequest());
      var original =
          catalog.createIndex("user", indexRequest(collection.getCollectionId(), "create"));
      var drop =
          DropIndexRequest.newBuilder()
              .setRequestId("drop")
              .setIndexId(original.getIndexId())
              .setExpectedRevision(1)
              .build();

      catalog.dropIndex("user", drop);
      var replacement =
          catalog.createIndex("user", indexRequest(collection.getCollectionId(), "replacement"));

      assertThat(replacement.getIndexId()).isNotEqualTo(original.getIndexId());
      assertThat(catalog.getIndex(original.getIndexId()).getLifecycleState()).isEqualTo("DROPPED");
      assertThat(catalog.dropIndex("user", drop).getIndexId()).isEqualTo(original.getIndexId());
      assertThat(catalog.getIndex(replacement.getIndexId()).getLifecycleState())
          .isEqualTo("PENDING");
    }
  }

  private static Status.Code update(
      LocalAuthoritativeCatalog catalog, String indexId, String requestId) {
    try {
      catalog.updateIndex(
          "user",
          UpdateIndexRequest.newBuilder()
              .setRequestId(requestId)
              .setIndexId(indexId)
              .setExpectedRevision(1)
              .setDefinitionBson(bson("{mappings: {dynamic: true}, storedSource: true}"))
              .build());
      return Status.Code.OK;
    } catch (StatusRuntimeException e) {
      return e.getStatus().getCode();
    }
  }

  private static CreateCollectionRequest collectionRequest() {
    return CreateCollectionRequest.newBuilder()
        .setRequestId("collection")
        .setDatabase("database")
        .setName("collection")
        .build();
  }

  private static CreateIndexRequest indexRequest(String collectionId, String requestId) {
    return CreateIndexRequest.newBuilder()
        .setRequestId(requestId)
        .setCollectionId(collectionId)
        .setName("index")
        .setType(IndexType.INDEX_TYPE_SEARCH)
        .setDefinitionBson(bson("{mappings: {dynamic: true}}"))
        .build();
  }

  private static ByteString bson(String json) {
    return ByteString.copyFrom(StandaloneDefinitionValidator.encode(BsonDocument.parse(json)));
  }
}
