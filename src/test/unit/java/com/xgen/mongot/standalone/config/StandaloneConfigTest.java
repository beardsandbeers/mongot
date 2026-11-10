package com.xgen.mongot.standalone.config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class StandaloneConfigTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void fromFile_minimalConfig_usesLocalStorageAndLoopbackDefaults() throws Exception {
    Path file = config("storage:\n  dataPath: data\n");

    var parsed = StandaloneConfig.fromFile(file);

    assertThat(parsed.address().getAddress().isLoopbackAddress()).isTrue();
    assertThat(parsed.dataPath()).isEqualTo(this.folder.getRoot().toPath().resolve("data"));
    assertThat(parsed.documentLimits().maxBatchDocuments()).isEqualTo(1000);
  }

  @Test
  public void fromFile_nonLoopbackWithoutSecurity_rejects() throws Exception {
    Path file =
        config("storage:\n  dataPath: data\nserver:\n  grpc:\n    address: '0.0.0.0:27029'\n");

    assertThrows(IllegalArgumentException.class, () -> StandaloneConfig.fromFile(file));
  }

  @Test
  public void fromFile_duplicateSettings_rejects() throws Exception {
    Path file = config("storage:\n  dataPath: first\n  dataPath: second\n");

    assertThrows(IOException.class, () -> StandaloneConfig.fromFile(file));
  }

  @Test
  public void fromFile_unknownMongoDbSyncSource_rejects() throws Exception {
    Path file = config("storage:\n  dataPath: data\nsyncSource: 'mongodb://localhost'\n");

    assertThrows(IOException.class, () -> StandaloneConfig.fromFile(file));
  }

  @Test
  public void fromFile_acceptanceOnlyCompletion_rejects() throws Exception {
    Path file = config("storage:\n  dataPath: data\ningestion:\n  writeCompletion: durable\n");

    assertThrows(IllegalArgumentException.class, () -> StandaloneConfig.fromFile(file));
  }

  @Test
  public void fromFile_bearerPrincipal_retainsDigestAndExplicitPermissions() throws Exception {
    Files.writeString(
        this.folder.getRoot().toPath().resolve("token"), "test-token-" + "x".repeat(32));
    Path file =
        config(
            "storage:\n"
                + "  dataPath: data\n"
                + "server:\n"
                + "  grpc:\n"
                + "    principals:\n"
                + "      - name: app\n"
                + "        tokenFile: token\n"
                + "        roles: [write]\n"
                + "        collections: ['*']\n");

    var parsed = StandaloneConfig.fromFile(file);

    assertThat(parsed.principals()).hasSize(1);
    assertThat(parsed.principals().getFirst().tokenDigest()).hasLength(32);
    assertThat(parsed.principals().getFirst().roles()).containsExactly(StandaloneConfig.Role.WRITE);
  }

  private Path config(String yaml) throws IOException {
    return Files.writeString(this.folder.getRoot().toPath().resolve("standalone.yaml"), yaml);
  }
}
