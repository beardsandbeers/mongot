package com.xgen.mongot.util.mongodb;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class SyncSourceConfig {

  /**
   * Direct connection to one mongod instance. Not for general read/write usage against the replica
   * set.
   */
  public final ConnectionInfo mongodUri;

  /**
   * A read only URI that depending on whether we're in a coupled vs dedicated deployment will
   * either be a direct connection to the coupled mongod instance or a replica-set connection if
   * we're in a dedicated cluster.
   *
   * <p>In a dedicated environment (dedicated + community) this will have directConnection=false and
   * go through replica set discovery to talk to any instance in the cluster. In the coupled
   * environment has directConnection=true tying the connection to the dedicated host we're deployed
   * with.
   */
  public final ConnectionInfo mongodClusterReaderUri;

  /**
   * Connection string with directConnection=false allowing the driver to go through replica-set
   * discovery and connect to any of the mongod instances in the cluster.
   *
   * <p>Since directConnection is always false, this is suitable for reading or writing from the
   * cluster as the driver will discover the primary instance via replica set discovery.
   */
  public final ConnectionInfo mongodClusterReadWriteUri;

  /** Optional mongos when the sync source is a sharded cluster. */
  public final Optional<ConnectionInfo> mongosUri;

  /** Optional map of host key → direct URI for ordered per-host access. */
  public final Optional<Map<String, ConnectionInfo>> mongodUris;

  public SyncSourceConfig(
      ConnectionInfo mongodUri,
      ConnectionInfo mongodClusterReaderUri,
      ConnectionInfo mongodClusterReadWriteUri,
      Optional<ConnectionInfo> mongosUri,
      Optional<Map<String, ConnectionInfo>> mongodUris) {
    this.mongodUri = mongodUri;
    this.mongodClusterReaderUri = mongodClusterReaderUri;
    this.mongodClusterReadWriteUri = mongodClusterReadWriteUri;
    this.mongosUri = mongosUri;
    this.mongodUris = mongodUris;
  }

  public SyncSourceConfig(
      ConnectionInfo mongodUri,
      ConnectionInfo mongodClusterReaderUri,
      Optional<ConnectionInfo> mongosUri,
      Optional<Map<String, ConnectionInfo>> mongodUris) {
    this(
        mongodUri,
        mongodClusterReaderUri,
        new ConnectionInfo(
            ConnectionStringUtil.disableDirectConnection(mongodClusterReaderUri.uri()),
            mongodClusterReaderUri.sslContext()),
        mongosUri,
        mongodUris);
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
        && this.mongodClusterReaderUri.equals(that.mongodClusterReaderUri)
        && this.mongodClusterReadWriteUri.equals(that.mongodClusterReadWriteUri)
        && this.mongodUris.equals(that.mongodUris);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        this.mongodUri,
        this.mongosUri,
        this.mongodClusterReaderUri,
        this.mongodClusterReadWriteUri,
        this.mongodUris);
  }
}
