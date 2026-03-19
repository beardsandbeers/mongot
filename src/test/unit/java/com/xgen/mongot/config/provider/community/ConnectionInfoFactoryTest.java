package com.xgen.mongot.config.provider.community;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.net.HostAndPort;
import com.mongodb.ConnectionString;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.xgen.mongot.util.mongodb.ConnectionInfo;
import com.xgen.mongot.util.mongodb.Databases;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class ConnectionInfoFactoryTest {

  private static final List<HostAndPort> HOSTS =
      List.of(HostAndPort.fromParts("localhost", 27017), HostAndPort.fromParts("localhost", 27018));

  private static ReplicaSetConfig replicaSetConfig(
      Optional<String> username,
      Optional<Path> passwordFile,
      Optional<X509Config> x509,
      boolean tls) {
    return new ReplicaSetConfig(
        HOSTS,
        username,
        passwordFile,
        Databases.ADMIN,
        tls,
        MongoReadPreferenceName.SECONDARY_PREFERRED,
        x509);
  }

  private static ReplicaSetConfig replicaSetConfig(
      List<HostAndPort> hosts,
      Optional<String> username,
      Optional<Path> passwordFile,
      Optional<X509Config> x509,
      boolean tls) {
    return new ReplicaSetConfig(
        hosts,
        username,
        passwordFile,
        Databases.ADMIN,
        tls,
        MongoReadPreferenceName.SECONDARY_PREFERRED,
        x509);
  }

  private static RouterConfig routerConfig(
      Optional<String> username, Optional<Path> passwordFile, Optional<X509Config> x509) {
    return new RouterConfig(
        HOSTS,
        username,
        passwordFile,
        Databases.ADMIN,
        false,
        MongoReadPreferenceName.PRIMARY,
        x509);
  }

  private static Path createPasswordFile(String password) throws IOException {
    Path temp = Files.createTempFile("mongot-connection-info-test", ".pass");
    Files.writeString(temp, password);
    try {
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("r--------"));
    } catch (UnsupportedOperationException ignored) {
      // POSIX permissions not supported on this filesystem (e.g., Windows)
    }
    return temp;
  }

  @Test
  public void getConnectionInfo_replicaSet_usernamePassword_parsesAsExpectedClusterUri()
      throws IOException {
    Path passwordFile = createPasswordFile("secret"); // kingfisher:ignore
    try {
      ReplicaSetConfig config =
          replicaSetConfig(
              Optional.of("testuser"), Optional.of(passwordFile), Optional.empty(), false);

      ConnectionInfo info =
          ConnectionInfoFactory.getConnectionInfo(config, Optional.empty(), false);

      ConnectionString cs = new ConnectionString(info.uri().getConnectionString());
      assertThat(cs.getHosts()).containsExactly("localhost:27017", "localhost:27018");
      assertThat(cs.getReadPreference()).isEqualTo(ReadPreference.secondaryPreferred());
      assertThat(cs.getReadConcern()).isEqualTo(ReadConcern.MAJORITY);
      assertThat(cs.getCredential()).isNotNull();
      assertThat(cs.getCredential().getUserName()).isEqualTo("testuser");
      assertThat(cs.getCredential().getSource()).isEqualTo("admin");
      String raw = info.uri().getConnectionString();
      assertThat(raw).contains("tls=false");
      assertThat(raw).contains("directConnection=false");
      assertThat(raw).contains("readConcernLevel=majority");
      assertThat(info.sslContext()).isEmpty();
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }

  @Test
  public void getConnectionInfo_replicaSet_tlsTrue_uriContainsTlsTrue() throws IOException {
    Path passwordFile = createPasswordFile("pass"); // kingfisher:ignore
    try {
      ReplicaSetConfig config =
          replicaSetConfig(Optional.of("u"), Optional.of(passwordFile), Optional.empty(), true);

      ConnectionInfo info =
          ConnectionInfoFactory.getConnectionInfo(config, Optional.empty(), false);

      ConnectionString cs = new ConnectionString(info.uri().getConnectionString());
      assertThat(cs.getReadConcern()).isEqualTo(ReadConcern.MAJORITY);
      assertThat(info.uri().getConnectionString()).contains("tls=true");
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }

  @Test
  public void getConnectionInfo_router_primaryReadPreference() throws IOException {
    Path passwordFile = createPasswordFile("p"); // kingfisher:ignore
    try {
      RouterConfig config =
          routerConfig(Optional.of("u"), Optional.of(passwordFile), Optional.empty());

      ConnectionInfo info =
          ConnectionInfoFactory.getConnectionInfo(config, Optional.empty(), false);

      ConnectionString cs = new ConnectionString(info.uri().getConnectionString());
      assertThat(cs.getReadPreference()).isEqualTo(ReadPreference.primary());
      assertThat(cs.getReadConcern()).isEqualTo(ReadConcern.MAJORITY);
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }

  @Test
  public void getConnectionInfo_router_directConnect_singleHostInUri() throws IOException {
    Path passwordFile = createPasswordFile("p"); // kingfisher:ignore
    try {
      RouterConfig config =
          routerConfig(Optional.of("u"), Optional.of(passwordFile), Optional.empty());

      ConnectionInfo info = ConnectionInfoFactory.getConnectionInfo(config, Optional.empty(), true);

      ConnectionString cs = new ConnectionString(info.uri().getConnectionString());
      assertThat(cs.getHosts()).hasSize(1);
      assertThat(cs.getHosts().get(0)).isAnyOf("localhost:27017", "localhost:27018");
      assertThat(info.uri().getConnectionString()).contains("directConnection=true");
      assertThat(info.uri().getConnectionString()).doesNotContain("readPreference");
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }

  @Test
  public void getConnectionInfo_X509ConfigWithoutCaFile_ThrowsIllegalArgumentException() {
    X509Config x509Config = new X509Config(Path.of("/etc/certs/client.pem"), Optional.empty());
    ReplicaSetConfig config =
        replicaSetConfig(Optional.empty(), Optional.empty(), Optional.of(x509Config), true);

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> ConnectionInfoFactory.getConnectionInfo(config, Optional.empty(), false));

    assertThat(e).hasMessageThat().contains("caFile must be present with x509");
  }

  @Test
  public void getConnectionInfo_directConnect_multiHost_singleHostChosenByRandom()
      throws IOException {
    Path passwordFile = createPasswordFile("p"); // kingfisher:ignore
    try {
      ReplicaSetConfig config =
          replicaSetConfig(Optional.of("u"), Optional.of(passwordFile), Optional.empty(), false);

      ConnectionInfo info = ConnectionInfoFactory.getConnectionInfo(config, Optional.empty(), true);

      // Host is selected with ThreadLocalRandom; only one of the configured hosts appears.
      ConnectionString cs = new ConnectionString(info.uri().getConnectionString());
      assertThat(cs.getHosts()).hasSize(1);
      assertThat(cs.getHosts().get(0)).isAnyOf("localhost:27017", "localhost:27018");
      String uri = info.uri().getConnectionString();
      assertThat(uri).contains("directConnection=true");
      assertThat(uri).doesNotContain("readPreference");
      assertThat(uri).doesNotContain("27017,localhost");
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }

  @Test
  public void getConnectionInfo_directConnect_singleReplicaHost_uriHasOnlyThatHost()
      throws IOException {
    Path passwordFile = createPasswordFile("p"); // kingfisher:ignore
    try {
      List<HostAndPort> oneHost = List.of(HostAndPort.fromParts("sync.example", 27019));
      ReplicaSetConfig config =
          replicaSetConfig(
              oneHost, Optional.of("u"), Optional.of(passwordFile), Optional.empty(), false);

      ConnectionInfo info = ConnectionInfoFactory.getConnectionInfo(config, Optional.empty(), true);

      ConnectionString cs = new ConnectionString(info.uri().getConnectionString());
      assertThat(cs.getHosts()).containsExactly("sync.example:27019");
      assertThat(info.uri().getConnectionString()).contains("directConnection=true");
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }
}
