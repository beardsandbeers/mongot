package com.xgen.mongot.config.provider.community;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.util.bson.parser.BsonDocumentParser;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import java.nio.file.Path;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.Test;

/** Unit tests for {@link X509Config} parsing and round-trip. */
public class X509AuthConfigTest {

  @Test
  public void parse_valid_onlyTlsCertificateKeyFile() throws BsonParseException {
    BsonDocument doc =
        new BsonDocument("tlsCertificateKeyFile", new BsonString("/etc/mongot/tls/client.pem"));

    try (var parser = BsonDocumentParser.fromRoot(doc).build()) {
      X509Config parsed = X509Config.fromBson(parser);
      assertEquals(Path.of("/etc/mongot/tls/client.pem"), parsed.tlsCertificateKeyFile());
      assertTrue(
          "tlsCertificateKeyFilePasswordFile should be empty when omitted",
          parsed.tlsCertificateKeyFilePasswordFile().isEmpty());
    }
  }

  @Test
  public void parse_valid_withPasswordFile() throws BsonParseException {
    BsonDocument doc =
        new BsonDocument()
            .append("tlsCertificateKeyFile", new BsonString("/etc/mongot/tls/client-combined.pem"))
            .append(
                "tlsCertificateKeyFilePasswordFile",
                new BsonString("/etc/mongot/secrets/cert-key-pass"));

    try (var parser = BsonDocumentParser.fromRoot(doc).build()) {
      X509Config parsed = X509Config.fromBson(parser);
      assertEquals(Path.of("/etc/mongot/tls/client-combined.pem"), parsed.tlsCertificateKeyFile());
      assertTrue(parsed.tlsCertificateKeyFilePasswordFile().isPresent());
      assertEquals(
          Path.of("/etc/mongot/secrets/cert-key-pass"),
          parsed.tlsCertificateKeyFilePasswordFile().get());
    }
  }

  @Test
  public void parse_missingTlsCertificateKeyFile_throws() throws BsonParseException {
    BsonDocument doc =
        new BsonDocument()
            .append(
                "tlsCertificateKeyFilePasswordFile",
                new BsonString("/etc/mongot/secrets/cert-key-pass"));

    var parser = BsonDocumentParser.fromRoot(doc).build();
    @Var BsonParseException caught = null;
    try {
      X509Config.fromBson(parser);
    } catch (BsonParseException e) {
      caught = e;
    }
    try {
      parser.close();
    } catch (BsonParseException e) {
      if (caught == null) {
        caught = e;
      }
    }
    assertNotNull("Expected BsonParseException from fromBson() or close()", caught);
    assertTrue(
        "Expected message about missing required field or unrecognized field",
        caught.getMessage() == null
            || caught.getMessage().contains("tlsCertificateKeyFile")
            || caught.getMessage().contains("tlsCertificateKeyFilePasswordFile"));
  }

  @Test
  public void roundTrip_toBsonFromBson_preservesValue() throws BsonParseException {
    BsonDocument doc =
        new BsonDocument()
            .append("tlsCertificateKeyFile", new BsonString("/etc/tls/client.pem"))
            .append("tlsCertificateKeyFilePasswordFile", new BsonString("/etc/secrets/key-pass"));

    try (var parser = BsonDocumentParser.fromRoot(doc).build()) {
      X509Config parsed = X509Config.fromBson(parser);
      BsonDocument encoded = parsed.toBson();

      try (var parser2 = BsonDocumentParser.fromRoot(encoded).build()) {
        X509Config roundTripped = X509Config.fromBson(parser2);
        assertEquals(parsed.tlsCertificateKeyFile(), roundTripped.tlsCertificateKeyFile());
        assertEquals(
            parsed.tlsCertificateKeyFilePasswordFile(),
            roundTripped.tlsCertificateKeyFilePasswordFile());
      }
    }
  }

  @Test
  public void roundTrip_onlyCertificateKeyFile_preservesValue() throws BsonParseException {
    BsonDocument doc =
        new BsonDocument("tlsCertificateKeyFile", new BsonString("/etc/tls/client-only.pem"));

    try (var parser = BsonDocumentParser.fromRoot(doc).build()) {
      X509Config parsed = X509Config.fromBson(parser);
      BsonDocument encoded = parsed.toBson();

      try (var parser2 = BsonDocumentParser.fromRoot(encoded).build()) {
        X509Config roundTripped = X509Config.fromBson(parser2);
        assertEquals(Path.of("/etc/tls/client-only.pem"), roundTripped.tlsCertificateKeyFile());
        assertTrue(roundTripped.tlsCertificateKeyFilePasswordFile().isEmpty());
      }
    }
  }
}
