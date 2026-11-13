package com.xgen.mongot.standalone.server;

import com.xgen.mongot.standalone.catalog.LocalAuthoritativeCatalog;
import com.xgen.mongot.standalone.config.StandaloneConfig;
import com.xgen.mongot.standalone.config.StandaloneConfig.Principal;
import com.xgen.mongot.standalone.config.StandaloneConfig.Role;
import com.xgen.mongot.standalone.metrics.StandaloneMetrics;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Authentication, role checks, transport-call admission and server-enforced RPC deadlines. */
final class StandaloneAuthInterceptor implements ServerInterceptor {
  private static final Context.Key<Principal> PRINCIPAL = Context.key("standalone-principal");
  private static final Context.Key<String> OPERATION = Context.key("standalone-operation");
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final Principal LOCAL =
      new Principal("local", new byte[0], Set.of(Role.values()), Set.of("*"));
  private final StandaloneConfig config;
  private final ScheduledExecutorService deadlines;
  private final Semaphore transportCalls;
  private final StandaloneMetrics metrics;

  StandaloneAuthInterceptor(
      StandaloneConfig config, ScheduledExecutorService deadlines, StandaloneMetrics metrics) {
    this.config = config;
    this.deadlines = deadlines;
    this.transportCalls = new Semaphore(config.maxInFlightRequests() + 16);
    this.metrics = metrics;
  }

  static Principal principal() {
    return Optional.ofNullable(PRINCIPAL.get())
        .orElseThrow(() -> Status.UNAUTHENTICATED.asRuntimeException());
  }

  static String operation() {
    return Optional.ofNullable(OPERATION.get()).orElse("unknown");
  }

  static void requireRole(Role role) {
    if (!principal().roles().contains(role)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Principal requires the " + role.name() + " role")
          .asRuntimeException();
    }
  }

  static void requireCollection(String collectionId) {
    if (!principal().allows(collectionId)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Principal cannot access this collection")
          .asRuntimeException();
    }
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    Principal principal;
    try {
      principal = authenticate(headers);
      String service = call.getMethodDescriptor().getServiceName();
      Role role =
          service != null
                  && (service.endsWith("CatalogService") || service.endsWith("OperationsService"))
              ? Role.CATALOG
              : service != null && service.endsWith("DocumentService") ? Role.WRITE : Role.QUERY;
      if (!"grpc.health.v1.Health".equals(service) && !principal.roles().contains(role)) {
        throw Status.PERMISSION_DENIED
            .withDescription("Principal is not authorized for this service")
            .asRuntimeException();
      }
    } catch (io.grpc.StatusRuntimeException e) {
      this.metrics.count("authentication.failures", 1, "status", e.getStatus().getCode().name());
      call.close(e.getStatus(), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    if (!this.transportCalls.tryAcquire()) {
      this.metrics.count("rpc.rejected", 1, "stage", "transport");
      call.close(
          Status.RESOURCE_EXHAUSTED.withDescription("Too many concurrent RPCs"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    var context =
        Context.current()
            .withValue(PRINCIPAL, principal)
            .withValue(OPERATION, call.getMethodDescriptor().getBareMethodName())
            .withDeadlineAfter(
                this.config.maxRpcDuration().toMillis(), TimeUnit.MILLISECONDS, this.deadlines);
    var released = new java.util.concurrent.atomic.AtomicBoolean();
    Runnable release =
        () -> {
          if (released.compareAndSet(false, true)) {
            this.transportCalls.release();
            context.cancel(null);
          }
        };
    try {
      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
          Contexts.interceptCall(context, call, headers, next)) {
        @Override
        public void onComplete() {
          try {
            super.onComplete();
          } finally {
            release.run();
          }
        }

        @Override
        public void onCancel() {
          try {
            super.onCancel();
          } finally {
            release.run();
          }
        }
      };
    } catch (RuntimeException e) {
      release.run();
      throw e;
    }
  }

  private Principal authenticate(Metadata headers) {
    List<Principal> principals = this.config.principals();
    if (principals.isEmpty()) {
      return LOCAL;
    }
    String authorization = headers.get(AUTHORIZATION);
    if (authorization == null
        || !authorization.startsWith("Bearer ")
        || authorization.length() > 4103
        || authorization.substring(7).chars().anyMatch(value -> value < 33 || value > 126)) {
      throw Status.UNAUTHENTICATED
          .withDescription("A bearer token is required")
          .asRuntimeException();
    }
    byte[] digest =
        LocalAuthoritativeCatalog.digest(
            authorization.substring(7).getBytes(StandardCharsets.US_ASCII));
    return principals.stream()
        .filter(principal -> MessageDigest.isEqual(principal.tokenDigest(), digest))
        .findFirst()
        .orElseThrow(
            () ->
                Status.UNAUTHENTICATED
                    .withDescription("Invalid bearer token")
                    .asRuntimeException());
  }
}
