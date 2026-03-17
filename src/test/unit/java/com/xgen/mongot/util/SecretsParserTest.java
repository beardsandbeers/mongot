package com.xgen.mongot.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.errorprone.annotations.Var;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.Assume;
import org.junit.Test;

public class SecretsParserTest {

  /**
   * Creates a temp secret file with owner-only read permission. On non-POSIX filesystems (e.g.
   * Windows), permissions are not set and the file may be more permissive; callers that require
   * POSIX for the test should use Assume.assumeTrue after checking.
   */
  private static Path createSecretFile(String content) throws IOException {
    Path temp = Files.createTempFile("mongot-secrets-parser-test", ".txt");
    Files.writeString(temp, content);
    try {
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("r--------"));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem (e.g. Windows)
    }
    return temp;
  }

  @Test
  public void readSecretFile_validFile_returnsTrimmedContent() throws IOException {
    Path file = createSecretFile("mysecret");

    try {
      String result = SecretsParser.readSecretFile(file);

      assertThat(result).isEqualTo("mysecret");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void readSecretFile_contentWithTrailingNewline_returnsTrimmedContent() throws IOException {
    Path file = createSecretFile("mysecret\n");

    try {
      String result = SecretsParser.readSecretFile(file);

      assertThat(result).isEqualTo("mysecret");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void readSecretFile_contentWithLeadingAndTrailingWhitespace_returnsTrimmedContent()
      throws IOException {
    Path file = createSecretFile("  secret  \n\t");

    try {
      String result = SecretsParser.readSecretFile(file);

      assertThat(result).isEqualTo("secret");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void readSecretFile_emptyFile_returnsEmptyString() throws IOException {
    Path file = createSecretFile("");

    try {
      String result = SecretsParser.readSecretFile(file);

      assertThat(result).isEmpty();
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void readSecretFile_fileDoesNotExist_throwsIllegalArgumentException() {
    Path nonexistent = Path.of("nonexistent-secret-file-" + System.nanoTime());

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> SecretsParser.readSecretFile(nonexistent));

    assertThat(thrown).hasMessageThat().contains("does not exist");
  }

  @Test
  public void readSecretFile_pathIsDirectory_throwsIllegalArgumentException() throws IOException {
    Path dir = Files.createTempDirectory("mongot-secrets-parser-test-dir");

    try {
      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, () -> SecretsParser.readSecretFile(dir));

      assertThat(thrown).hasMessageThat().contains("is not a file");
    } finally {
      Files.deleteIfExists(dir);
    }
  }

  @Test
  public void readSecretFile_permissionsTooPermissive_throwsIllegalArgumentException()
      throws IOException {
    Path file = Files.createTempFile("mongot-secrets-parser-test", ".txt");
    Files.writeString(file, "secret");
    @Var boolean posixSupported = false;
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r-----"));
      posixSupported = true;
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem
    }
    Assume.assumeTrue("POSIX permissions not supported on this filesystem", posixSupported);

    try {
      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, () -> SecretsParser.readSecretFile(file));

      assertThat(thrown).hasMessageThat().contains("permissions are too permissive");
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
