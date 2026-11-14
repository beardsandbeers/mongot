package com.xgen.mongot.standalone.catalog;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.IndexType;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.file.Files;
import org.bson.BsonDocument;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalIndexCatalogTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void reopen_preservesDefinitionsAndRevisionsWithoutContent() throws Exception {
    String id;
    var definition =
        ByteString.copyFrom(
            StandaloneDefinitionValidator.encode(BsonDocument.parse("{mappings:{dynamic:true}}")));
    var request =
        CreateIndexRequest.newBuilder()
            .setName("text")
            .setType(IndexType.INDEX_TYPE_SEARCH)
            .setDefinitionBson(definition)
            .build();
    try (var catalog = new LocalIndexCatalog(this.folder.getRoot().toPath())) {
      id = catalog.createIndex(request).getIndexId();
      assertThat(catalog.createIndex(request).getIndexId()).isEqualTo(id);
      catalog.updateIndex(
          UpdateIndexRequest.newBuilder()
              .setIndexId(id)
              .setExpectedRevision(1)
              .setDefinitionBson(definition)
              .build());
    }
    try (var catalog = new LocalIndexCatalog(this.folder.getRoot().toPath())) {
      assertThat(catalog.getIndex(id).getDesiredRevision()).isEqualTo(2);
      assertThat(
              assertThrows(
                      StatusRuntimeException.class,
                      () ->
                          catalog.dropIndex(
                              DropIndexRequest.newBuilder()
                                  .setIndexId(id)
                                  .setExpectedRevision(1)
                                  .build()))
                  .getStatus()
                  .getCode())
          .isEqualTo(Status.Code.ABORTED);
      catalog.dropIndex(
          DropIndexRequest.newBuilder().setIndexId(id).setExpectedRevision(2).build());
    }
    try (var catalog = new LocalIndexCatalog(this.folder.getRoot().toPath())) {
      assertThat(catalog.definitions()).isEmpty();
      assertThat(catalog.createIndex(request).getIndexId()).isNotEqualTo(id);
    }
  }

  @Test
  public void legacyDirectory_failsWithoutChangingDataOrLeakingLock() throws Exception {
    var directory = this.folder.getRoot().toPath();
    var legacy = directory.resolve("standalone.sqlite");
    Files.writeString(legacy, "legacy data");
    assertThrows(IOException.class, () -> new LocalIndexCatalog(directory));
    assertThat(Files.readString(legacy)).isEqualTo("legacy data");
    assertThrows(IOException.class, () -> new LocalIndexCatalog(directory));
  }
}
