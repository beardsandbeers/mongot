package com.xgen.mongot.config.provider.community;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.net.HostAndPort;
import com.xgen.mongot.util.mongodb.Databases;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** Unit tests for {@link ConnectionInfoFactory}. */
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
  public void
      getConnectionInfo_ReplicaSetConfigWithUsernamePassword_ReturnsConnectionInfoWithExpectedUri()
          throws IOException {
    Path passwordFile = createPasswordFile("secret"); // kingfisher:ignore
    try {
      ReplicaSetConfig config =
          replicaSetConfig(
              Optional.of("testuser"), Optional.of(passwordFile), Optional.empty(), false);

      com.xgen.mongot.util.mongodb.ConnectionInfo info =
          ConnectionInfoFactory.getConnectionInfo(config, Optional.empty());

      String uri = info.uri().getConnectionString();
      assertThat(uri).contains("localhost:27017");
      assertThat(uri).contains("localhost:27018");
      assertThat(uri).contains("readPreference=secondaryPreferred");
      assertThat(uri).contains("directConnection=false");
      assertThat(uri).contains("/admin"); // auth database in path
      assertThat(uri).contains("testuser");
      assertThat(info.sslContext()).isEmpty();
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }

  @Test
  public void getConnectionInfo_ReplicaSetConfigWithTls_IncludesTlsOptionInUri()
      throws IOException {
    Path passwordFile = createPasswordFile("pass"); // kingfisher:ignore
    try {
      ReplicaSetConfig config =
          replicaSetConfig(Optional.of("u"), Optional.of(passwordFile), Optional.empty(), true);

      com.xgen.mongot.util.mongodb.ConnectionInfo info =
          ConnectionInfoFactory.getConnectionInfo(config, Optional.empty());

      assertThat(info.uri().getConnectionString()).contains("tls=true");
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }

  @Test
  public void getConnectionInfo_RouterConfig_ReturnsConnectionInfoWithPrimaryReadPreference()
      throws IOException {
    Path passwordFile = createPasswordFile("p"); // kingfisher:ignore
    try {
      RouterConfig config =
          routerConfig(Optional.of("u"), Optional.of(passwordFile), Optional.empty());

      com.xgen.mongot.util.mongodb.ConnectionInfo info =
          ConnectionInfoFactory.getConnectionInfo(config, Optional.empty());

      assertThat(info.uri().getConnectionString()).contains("readPreference=primary");
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
            () -> ConnectionInfoFactory.getConnectionInfo(config, Optional.empty()));

    assertThat(e).hasMessageThat().contains("caFile must be present with x509");
  }

  @Test
  public void getConnectionInfo_WithoutCaFile_SslContextIsEmpty() throws IOException {
    Path passwordFile = createPasswordFile("p"); // kingfisher:ignore
    try {
      ReplicaSetConfig config =
          replicaSetConfig(Optional.of("u"), Optional.of(passwordFile), Optional.empty(), false);

      com.xgen.mongot.util.mongodb.ConnectionInfo info =
          ConnectionInfoFactory.getConnectionInfo(config, Optional.empty());

      assertThat(info.sslContext()).isEmpty();
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }
}
