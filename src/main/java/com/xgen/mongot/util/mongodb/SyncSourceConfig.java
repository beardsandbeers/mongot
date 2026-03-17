package com.xgen.mongot.util.mongodb;

import com.mongodb.ConnectionString;
import com.xgen.mongot.util.CollectionUtils;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.TestOnly;

public class SyncSourceConfig {

  public final ConnectionInfo mongodUri;
  public final Optional<ConnectionInfo> mongosUri;
  public final ConnectionInfo mongodClusterUri;
  public final Optional<Map<String, ConnectionInfo>> mongodUris;

  public SyncSourceConfig(
      ConnectionInfo mongodUri,
      Optional<ConnectionInfo> mongosUri,
      ConnectionInfo mongodClusterUri,
      Optional<Map<String, ConnectionInfo>> mongodUris) {
    this.mongodUri = mongodUri;
    this.mongosUri = mongosUri;
    this.mongodClusterUri = mongodClusterUri;
    this.mongodUris = mongodUris;
  }

  public SyncSourceConfig(
      ConnectionString mongodUri,
      Optional<Map<String, ConnectionString>> mongodUris,
      Optional<ConnectionString> mongosUri,
      ConnectionString mongodClusterUri) {
    this(
        new ConnectionInfo(mongodUri),
        mongosUri.map(ConnectionInfo::new),
        new ConnectionInfo(mongodClusterUri),
        mongodUris.map(
            uris ->
                uris.entrySet().stream()
                    .collect(
                        CollectionUtils.toMapUnsafe(
                            Map.Entry::getKey, e -> new ConnectionInfo(e.getValue())))));
  }

  @TestOnly
  public SyncSourceConfig(
      ConnectionString mongodUri,
      Optional<ConnectionString> mongosUri,
      ConnectionString mongodClusterUri) {
    this(mongodUri, Optional.empty(), mongosUri, mongodClusterUri);
  }

  public SyncSourceConfig(
      ConnectionInfo mongodUri,
      Optional<ConnectionInfo> mongosUri,
      ConnectionInfo mongodClusterUri) {
    this(mongodUri, mongosUri, mongodClusterUri, Optional.empty());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SyncSourceConfig that = (SyncSourceConfig) o;
    return this.mongodUri.equals(that.mongodUri)
        && this.mongosUri.equals(that.mongosUri)
        && this.mongodClusterUri.equals(that.mongodClusterUri)
        && this.mongodUris.equals(that.mongodUris);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.mongodUri, this.mongosUri, this.mongodClusterUri, this.mongodUris);
  }
}
