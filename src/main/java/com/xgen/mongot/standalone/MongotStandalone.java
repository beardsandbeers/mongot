package com.xgen.mongot.standalone;

import com.xgen.mongot.index.lucene.vector.Similarity;
import com.xgen.mongot.logging.Logging;
import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.runtime.StandaloneRuntime;
import com.xgen.mongot.standalone.server.StandaloneGrpcServer;
import com.xgen.mongot.util.security.Security;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/** Independent mongot executable with a local catalog and synchronous document-write gRPC API. */
@CommandLine.Command(name = "mongot_standalone", mixinStandardHelpOptions = true)
public final class MongotStandalone implements Callable<Integer> {
  private static final Logger LOG = LoggerFactory.getLogger(MongotStandalone.class);

  @CommandLine.Option(
      names = "--config",
      required = true,
      description = "Standalone YAML configuration file")
  private Path configPath = Path.of("standalone.yaml");

  @Override
  public Integer call() throws Exception {
    if (Runtime.version().feature() != 21) {
      throw new IllegalStateException("mongot standalone requires Java 21");
    }
    Logging.bridgeJulToSlf4j();
    Security.installFipsSecurityProvider();
    Similarity.load();
    var config = StandaloneConfig.fromFile(this.configPath);
    try (var runtime = new StandaloneRuntime(config);
        var server = new StandaloneGrpcServer(runtime).start()) {
      var shutdown =
          new Thread(
              () -> {
                try {
                  server.close();
                  runtime.close();
                } catch (Exception e) {
                  LOG.error("Standalone shutdown failed", e);
                }
              },
              "standalone-shutdown");
      Runtime.getRuntime().addShutdownHook(shutdown);
      LOG.info(
          "Standalone gRPC listening on {}:{}",
          config.address().getAddress().getHostAddress(),
          server.port());
      try {
        server.awaitTermination();
      } finally {
        try {
          Runtime.getRuntime().removeShutdownHook(shutdown);
        } catch (IllegalStateException ignored) {
          /* Shutdown already owns the hook. */
        }
      }
    }
    return 0;
  }

  public static void main(String[] args) {
    System.exit(new CommandLine(new MongotStandalone()).execute(args));
  }
}
