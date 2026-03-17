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

public record SyncSourceConfig(
    ReplicaSetConfig replicaSet, Optional<RouterConfig> router, Optional<Path> caFile)
    implements DocumentEncodable {
  private static class Fields {
    public static final Field.Required<ReplicaSetConfig> REPLICA_SET =
        Field.builder("replicaSet")
            .classField(ReplicaSetConfig::fromBson, ReplicaSetConfig::toBson)
            .disallowUnknownFields()
            .required();

    public static final Field.Optional<RouterConfig> ROUTER =
        Field.builder("router")
            .classField(RouterConfig::fromBson, RouterConfig::toBson)
            .disallowUnknownFields()
            .optional()
            .noDefault();

    public static final Field.Optional<Path> CA_FILE =
        Field.builder("caFile")
            .classField(PathField.PARSER, PathField.ENCODER)
            .optional()
            .noDefault();
  }

  public static SyncSourceConfig fromBson(DocumentParser parser) throws BsonParseException {
    SyncSourceConfig syncSourceConfig =
        new SyncSourceConfig(
            parser.getField(Fields.REPLICA_SET).unwrap(),
            parser.getField(Fields.ROUTER).unwrap(),
            parser.getField(Fields.CA_FILE).unwrap());

    syncSourceConfig.replicaSet.validate(parser, syncSourceConfig.caFile);
    if (syncSourceConfig.router.isPresent()) {
      syncSourceConfig.router.get().validate(parser, syncSourceConfig.caFile);
    }
    return syncSourceConfig;
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(Fields.REPLICA_SET, this.replicaSet)
        .field(Fields.ROUTER, this.router)
        .field(Fields.CA_FILE, this.caFile)
        .build();
  }
}
