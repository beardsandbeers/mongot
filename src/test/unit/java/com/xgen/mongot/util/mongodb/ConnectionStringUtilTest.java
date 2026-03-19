package com.xgen.mongot.util.mongodb;

import static com.google.common.truth.Truth.assertThat;

import com.mongodb.ConnectionString;
import org.junit.Assert;
import org.junit.Test;

public class ConnectionStringUtilTest {

  @Test
  public void testDoesNotThrowForValidConnectionString() throws Exception {
    ConnectionStringUtil.toConnectionInfo("mongodb://localhost:27017");
  }

  @Test
  public void testThrowsForInvalidConnectionString() {
    Assert.assertThrows(
        ConnectionStringUtil.InvalidConnectionStringException.class,
        () -> ConnectionStringUtil.toConnectionInfo("this isn't valid"));
  }

  @Test
  public void disableDirectConnection_whenDirectConnectionAbsent_returnsSameInstance() {
    ConnectionString original = new ConnectionString("mongodb://host1:27017,host2:27017");
    ConnectionString result = ConnectionStringUtil.disableDirectConnection(original);
    assertThat(result).isSameInstanceAs(original);
  }

  @Test
  public void disableDirectConnection_whenDirectConnectionFalse_returnsSameInstance() {
    ConnectionString original =
        new ConnectionString("mongodb://host:27017/?directConnection=false");
    ConnectionString result = ConnectionStringUtil.disableDirectConnection(original);
    assertThat(result).isSameInstanceAs(original);
  }

  @Test
  public void disableDirectConnection_whenDirectConnectionTrue_setsDirectConnectionFalse() {
    ConnectionString original = new ConnectionString("mongodb://host:27017/?directConnection=true");
    ConnectionString result = ConnectionStringUtil.disableDirectConnection(original);
    assertThat(result).isNotSameInstanceAs(original);
    assertThat(result.isDirectConnection()).isFalse();
    assertThat(result.getConnectionString()).contains("directConnection=false");
    assertThat(result.getConnectionString()).doesNotContain("directConnection=true");
  }

  @Test
  public void disableDirectConnection_whenDirectConnectionTrue_caseInsensitive_replaces() {
    ConnectionString original = new ConnectionString("mongodb://host:27017/?DIRECTCONNECTION=TRUE");
    ConnectionString result = ConnectionStringUtil.disableDirectConnection(original);
    assertThat(result.isDirectConnection()).isFalse();
    assertThat(result.getConnectionString().toLowerCase()).contains("directconnection=false");
  }

  @Test
  public void disableDirectConnection_whenDirectConnectionTrue_preservesOtherOptions() {
    ConnectionString original =
        new ConnectionString("mongodb://host:27017/?directConnection=true&tls=true&replicaSet=rs0");
    ConnectionString result = ConnectionStringUtil.disableDirectConnection(original);
    assertThat(result.isDirectConnection()).isFalse();
    assertThat(result.getConnectionString()).contains("tls=true");
    assertThat(result.getConnectionString()).contains("replicaSet=rs0");
  }

  @Test
  public void disableDirectConnection_whenDirectConnectionTrue_notFirstQueryArg_stillReplaces() {
    ConnectionString original =
        new ConnectionString("mongodb://host:27017/?tls=true&replicaSet=rs0&directConnection=true");
    ConnectionString result = ConnectionStringUtil.disableDirectConnection(original);
    assertThat(result.isDirectConnection()).isFalse();
    assertThat(result.getConnectionString()).contains("tls=true");
    assertThat(result.getConnectionString()).contains("replicaSet=rs0");
    assertThat(result.getConnectionString()).contains("directConnection=false");
    assertThat(result.getConnectionString()).doesNotContain("directConnection=true");
  }
}
