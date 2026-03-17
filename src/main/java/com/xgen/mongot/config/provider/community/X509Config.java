package com.xgen.mongot.config.provider.community;

import com.xgen.mongot.config.provider.community.parser.PathField;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentEncodable;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import java.nio.file.Path;
import java.util.Optional;
import org.bson.BsonDocument;

public record X509Config(
    Path tlsCertificateKeyFile, Optional<Path> tlsCertificateKeyFilePasswordFile)
    implements DocumentEncodable {
  private static class Fields {
    public static final Field.Required<Path> TLS_CERTIFICATE_KEY_FILE =
        Field.builder("tlsCertificateKeyFile")
            .classField(PathField.PARSER, PathField.ENCODER)
            .required();

    public static final Field.Optional<Path> TLS_CERTIFICATE_KEY_FILE_PASSWORD_FILE =
        Field.builder("tlsCertificateKeyFilePasswordFile")
            .classField(PathField.PARSER, PathField.ENCODER)
            .optional()
            .noDefault();
  }

  public static X509Config fromBson(DocumentParser parser) throws BsonParseException {
    return new X509Config(
        parser.getField(Fields.TLS_CERTIFICATE_KEY_FILE).unwrap(),
        parser.getField(Fields.TLS_CERTIFICATE_KEY_FILE_PASSWORD_FILE).unwrap());
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(Fields.TLS_CERTIFICATE_KEY_FILE, this.tlsCertificateKeyFile)
        .field(
            Fields.TLS_CERTIFICATE_KEY_FILE_PASSWORD_FILE, this.tlsCertificateKeyFilePasswordFile)
        .build();
  }
}
