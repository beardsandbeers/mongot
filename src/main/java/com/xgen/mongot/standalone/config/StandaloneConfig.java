package com.xgen.mongot.standalone.config;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.net.HostAndPort;
import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.ingestion.DurableWriteLedger;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Standalone-only configuration. There is no sync source or MongoDB connection configuration. */
public record StandaloneConfig(
    Path dataPath,
    InetSocketAddress address,
    DurableWriteLedger.Limits documentLimits,
    int maxInFlightRequests,
    long maxInFlightBytes,
    long maxLogBytes,
    long minFreeDiskBytes,
    Duration commitInterval,
    Duration refreshInterval,
    Duration retryRetention,
    Duration maxRpcDuration,
    Optional<Tls> tls,
    List<Principal> principals) {

  public enum Role {
    CATALOG,
    WRITE,
    QUERY
  }

  public record Tls(Path certificate, Path privateKey) {}

  /** Only a token digest is retained in memory. Deduplication is scoped by the stable name. */
  public record Principal(
      String name, byte[] tokenDigest, Set<Role> roles, Set<String> collections) {
    public boolean allows(String collectionId) {
      return this.collections.contains("*") || this.collections.contains(collectionId);
    }
  }

  /** Validates resource limits and external-listener security before starting any services. */
  @SuppressWarnings("Var") // A compact record constructor normalizes its final component value.
  public StandaloneConfig {
    principals = List.copyOf(principals);
    if (address.isUnresolved()
        || (!address.getAddress().isLoopbackAddress() && (tls.isEmpty() || principals.isEmpty()))) {
      throw new IllegalArgumentException(
          "Non-loopback listeners require TLS and authenticated principals");
    }
    if (maxInFlightRequests < 1
        || maxInFlightRequests > 1024
        || maxInFlightBytes < documentLimits.maxBatchBytes()
        || maxLogBytes < documentLimits.maxBatchBytes()
        || minFreeDiskBytes < 0
        || commitInterval.isNegative()
        || commitInterval.isZero()
        || refreshInterval.isNegative()
        || refreshInterval.isZero()
        || retryRetention.isNegative()
        || retryRetention.isZero()
        || maxRpcDuration.isNegative()
        || maxRpcDuration.isZero()
        || documentLimits.maxDocumentBytes() > 16 * 1024 * 1024
        || documentLimits.maxBatchBytes() > Integer.MAX_VALUE - 65536) {
      throw new IllegalArgumentException("Standalone limits or timing configuration is invalid");
    }
  }

  /** Reads a bounded, strictly typed YAML file; unknown and duplicate settings are rejected. */
  public static StandaloneConfig fromFile(Path configPath) throws IOException {
    if (Files.size(configPath) > 65536) {
      throw new IllegalArgumentException("Standalone configuration exceeds 64 KiB");
    }
    var mapper =
        JsonMapper.builder(
                YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    var raw = mapper.readValue(Files.readString(configPath), FileConfig.class);
    if (!or(raw.mode(), "standalone").equals("standalone")) {
      throw new IllegalArgumentException("Configuration mode must be standalone");
    }
    Path base = java.util.Objects.requireNonNull(configPath.toAbsolutePath().getParent());
    var storage = or(raw.storage(), new Storage(null, null));
    if (storage.dataPath() == null || storage.dataPath().isBlank()) {
      throw new IllegalArgumentException("storage.dataPath is required");
    }
    var sourceStore = or(storage.sourceStore(), new SourceStore(null, null));
    if (!or(sourceStore.engine(), "sqlite").equals("sqlite")
        || !or(sourceStore.synchronous(), "full").equals("full")) {
      throw new IllegalArgumentException("Standalone requires SQLite with synchronous: full");
    }
    var server = or(raw.server(), new Server(null));
    var grpc = or(server.grpc(), new Grpc(null, null, null, null));
    var host =
        HostAndPort.fromString(or(grpc.address(), "127.0.0.1:27029")).requireBracketsForIPv6();
    var address = new InetSocketAddress(InetAddress.getByName(host.getHost()), host.getPort());
    Optional<Tls> tls =
        Optional.ofNullable(grpc.tls())
            .map(
                value ->
                    new Tls(resolve(base, value.certificate()), resolve(base, value.privateKey())));
    var principals = new ArrayList<Principal>();
    var names = new HashSet<String>();
    var digests = new HashSet<String>();
    for (var principal : or(grpc.principals(), List.<PrincipalConfig>of())) {
      if (principal.name().isBlank() || !names.add(principal.name())) {
        throw new IllegalArgumentException("Principal names must be nonempty and unique");
      }
      Path tokenPath = resolve(base, principal.tokenFile());
      if (Files.size(tokenPath) > 4097) {
        throw new IllegalArgumentException("Bearer token file is too large");
      }
      String token = Files.readString(tokenPath, StandardCharsets.UTF_8).strip();
      if (!token.matches("[!-~]{32,4096}")) {
        throw new IllegalArgumentException(
            "Bearer tokens must contain 32 to 4096 non-whitespace ASCII characters");
      }
      byte[] digest = LocalAuthoritativeCatalog.digest(token.getBytes(StandardCharsets.US_ASCII));
      if (!digests.add(java.util.HexFormat.of().formatHex(digest))) {
        throw new IllegalArgumentException("Bearer tokens must be unique across principals");
      }
      var roles = new HashSet<Role>();
      for (String role : principal.roles()) {
        roles.add(Role.valueOf(role.toUpperCase(java.util.Locale.ROOT)));
      }
      if (roles.isEmpty() || principal.collections().isEmpty()) {
        throw new IllegalArgumentException("Principals require explicit roles and collections");
      }
      for (String collection : principal.collections()) {
        if (!collection.equals("*")) {
          LocalAuthoritativeCatalog.validateCollectionId(collection);
        }
      }
      principals.add(
          new Principal(
              principal.name(), digest, Set.copyOf(roles), Set.copyOf(principal.collections())));
    }
    var ingestion =
        or(
            raw.ingestion(),
            new Ingestion(null, null, null, null, null, null, null, null, null, null, null));
    if (!or(ingestion.writeCompletion(), "indexed").equals("indexed")) {
      throw new IllegalArgumentException("Standalone writeCompletion must be indexed");
    }
    var querying = or(raw.querying(), new Querying(null));
    return new StandaloneConfig(
        resolve(base, storage.dataPath()),
        address,
        new DurableWriteLedger.Limits(
            or(ingestion.maxDocumentBytes(), 16777216),
            or(ingestion.maxNestingDepth(), 100),
            or(ingestion.maxBatchDocuments(), 1000),
            or(ingestion.maxBatchBytes(), 33554432)),
        or(ingestion.maxInFlightRequests(), 64),
        or(ingestion.maxQueuedBytes(), 268435456L),
        or(ingestion.maxUnindexedLogBytes(), 10737418240L),
        or(ingestion.minFreeDiskBytes(), 268435456L),
        Duration.ofMillis(or(ingestion.commitIntervalMillis(), 1000L)),
        Duration.ofMillis(or(querying.refreshIntervalMillis(), 1000L)),
        Duration.ofHours(or(ingestion.retryRetentionHours(), 24L)),
        Duration.ofMillis(or(grpc.maxRpcDurationMillis(), 60000L)),
        tls,
        principals);
  }

  public static StandaloneConfig development(Path dataPath, int port) {
    return new StandaloneConfig(
        dataPath,
        new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
        DurableWriteLedger.Limits.defaults(),
        64,
        268435456L,
        10737418240L,
        0,
        Duration.ofSeconds(1),
        Duration.ofMillis(50),
        Duration.ofHours(24),
        Duration.ofSeconds(60),
        Optional.empty(),
        List.of());
  }

  private static <T> T or(@Nullable T value, T fallback) {
    return value == null ? fallback : value;
  }

  private static Path resolve(Path base, String value) {
    return base.resolve(value).normalize().toAbsolutePath();
  }

  private record FileConfig(
      @Nullable String mode,
      @Nullable Storage storage,
      @Nullable Server server,
      @Nullable Ingestion ingestion,
      @Nullable Querying querying) {}

  private record Storage(@Nullable String dataPath, @Nullable SourceStore sourceStore) {}

  private record SourceStore(@Nullable String engine, @Nullable String synchronous) {}

  private record Server(@Nullable Grpc grpc) {}

  private record Grpc(
      @Nullable String address,
      @Nullable TlsConfig tls,
      @Nullable List<PrincipalConfig> principals,
      @Nullable Long maxRpcDurationMillis) {}

  private record TlsConfig(String certificate, String privateKey) {}

  private record PrincipalConfig(
      String name, String tokenFile, List<String> roles, List<String> collections) {}

  private record Querying(@Nullable Long refreshIntervalMillis) {}

  private record Ingestion(
      @Nullable String writeCompletion,
      @Nullable Integer maxInFlightRequests,
      @Nullable Integer maxDocumentBytes,
      @Nullable Integer maxNestingDepth,
      @Nullable Integer maxBatchDocuments,
      @Nullable Integer maxBatchBytes,
      @Nullable Long maxQueuedBytes,
      @Nullable Long maxUnindexedLogBytes,
      @Nullable Long minFreeDiskBytes,
      @Nullable Long commitIntervalMillis,
      @Nullable Long retryRetentionHours) {}
}
