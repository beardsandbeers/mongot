import com.xgen.mongot.standalone.grpc.GetStatusRequest;
import com.xgen.mongot.standalone.grpc.OperationsServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.netty.handler.ssl.SslProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Small authenticated health/status client compiled against the bundled service's exact API. */
public final class StandaloneControl {
  private StandaloneControl() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !(args[0].equals("health") || args[0].equals("status"))) {
      System.err.println("Usage: mongotctl health|status");
      System.exit(2);
    }
    var builder =
        NettyChannelBuilder.forTarget(setting("MONGOT_CONTROL_ADDRESS", "127.0.0.1:27029"));
    if (Boolean.parseBoolean(setting("MONGOT_CONTROL_PLAINTEXT", "false"))) {
      builder.usePlaintext();
    } else {
      builder.sslContext(
          GrpcSslContexts.forClient()
              .sslProvider(SslProvider.JDK)
              .trustManager(
                  Path.of(setting("MONGOT_CONTROL_CA", "/var/lib/mongot/config/server.crt"))
                      .toFile())
              .build());
    }
    var headers = new Metadata();
    String tokenPath = setting("MONGOT_CONTROL_TOKEN_FILE", "/var/lib/mongot/config/admin.token");
    if (!tokenPath.isEmpty()) {
      String token = Files.readString(Path.of(tokenPath)).strip();
      headers.put(
          Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
    }
    ManagedChannel channel = builder.build();
    try {
      var authentication = MetadataUtils.newAttachHeadersInterceptor(headers);
      if (args[0].equals("health")) {
        var health =
            HealthGrpc.newBlockingStub(channel)
                .withInterceptors(authentication)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .check(HealthCheckRequest.getDefaultInstance());
        if (health.getStatus() != ServingStatus.SERVING) {
          throw new IllegalStateException("Service reports " + health.getStatus());
        }
        System.out.println("SERVING");
      } else {
        var status =
            OperationsServiceGrpc.newBlockingStub(channel)
                .withInterceptors(authentication)
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .getStatus(GetStatusRequest.getDefaultInstance());
        System.out.print(status);
      }
    } finally {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static String setting(String name, String fallback) {
    String value = System.getenv(name);
    return value == null ? fallback : value;
  }
}
