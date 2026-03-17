package com.xgen.mongot.config.provider.community;

import com.mongodb.ConnectionString;
import com.xgen.mongot.util.Check;
import com.xgen.mongot.util.Crash;
import com.xgen.mongot.util.SecretsParser;
import com.xgen.mongot.util.mongodb.ConnectionInfo;
import com.xgen.mongot.util.mongodb.ConnectionStringBuilder;
import com.xgen.mongot.util.mongodb.SslContextFactory;
import java.nio.file.Path;
import java.util.Optional;
import javax.net.ssl.SSLContext;

public class ConnectionInfoFactory {

  public static ConnectionInfo getConnectionInfo(
      MongoConnectionConfig config, Optional<Path> caFile) {
    return new ConnectionInfo(getConnectionString(config), getSslContext(config, caFile));
  }

  private static ConnectionString getConnectionString(MongoConnectionConfig config) {
    ConnectionStringBuilder connectionStringBuilder =
        ConnectionStringBuilder.standard()
            .withHostAndPorts(config.hostandPorts())
            .withOption("tls", Boolean.toString(config.tls()))
            .withOption("readPreference", config.readPreference().asReadPreference().getName())
            // Per spec, this should be false by default, however, it is not: JAVA-4257
            .withOption("directConnection", "false");

    if (config.x509().isPresent()) {
      connectionStringBuilder.withX509Config();
    } else {
      String replicaSetPassword =
          Crash.because("failed to read password file")
              .ifThrows(() -> SecretsParser.readSecretFile(config.passwordFile().get()));

      connectionStringBuilder
          .withAuthenticationCredentials(config.username().get(), replicaSetPassword)
          .withAuthenticationDatabase(config.authSource());
    }

    return Crash.because("failed to construct connection string")
        .ifThrows(connectionStringBuilder::build);
  }

  private static Optional<SSLContext> getSslContext(
      MongoConnectionConfig connectionConfig, Optional<Path> caFile) {

    if (connectionConfig.x509().isPresent()) {
      Check.checkArg(caFile.isPresent(), "caFile must be present with x509");

      X509Config x509Config = connectionConfig.x509().get();
      return Optional.of(
          SslContextFactory.getWithCaAndCertificateFile(
              caFile.get(),
              x509Config.tlsCertificateKeyFile(),
              x509Config.tlsCertificateKeyFilePasswordFile()));
    } else {
      return caFile.map(SslContextFactory::getWithCaFile);
    }
  }
}
