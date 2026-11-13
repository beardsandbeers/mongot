package com.xgen.mongot.standalone.server;

import com.xgen.mongot.standalone.grpc.AwaitSequenceRequest;
import com.xgen.mongot.standalone.grpc.CatalogServiceGrpc;
import com.xgen.mongot.standalone.grpc.CollectionDescription;
import com.xgen.mongot.standalone.grpc.CreateCollectionRequest;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DocumentServiceGrpc;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.GetCollectionRequest;
import com.xgen.mongot.standalone.grpc.GetIndexRequest;
import com.xgen.mongot.standalone.grpc.GetStatusRequest;
import com.xgen.mongot.standalone.grpc.GetWriteResultRequest;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.IndexWatermarks;
import com.xgen.mongot.standalone.grpc.ListIndexesRequest;
import com.xgen.mongot.standalone.grpc.ListIndexesResponse;
import com.xgen.mongot.standalone.grpc.OperationsServiceGrpc;
import com.xgen.mongot.standalone.grpc.QueryServiceGrpc;
import com.xgen.mongot.standalone.grpc.ServiceStatus;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.grpc.WriteBatchRequest;
import com.xgen.mongot.standalone.grpc.WriteRequest;
import com.xgen.mongot.standalone.grpc.WriteResponse;
import com.xgen.mongot.standalone.runtime.DocumentWriteCoordinator;
import com.xgen.mongot.standalone.runtime.StandaloneRuntime;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Ordinary generated protobuf services on a dedicated standalone listener. */
public final class StandaloneGrpcServer implements AutoCloseable {
  private final StandaloneRuntime runtime;
  private final RpcDispatcher dispatcher;
  private final DocumentWriteCoordinator writes;
  private final ScheduledThreadPoolExecutor deadlines;
  private final HealthStatusManager health = new HealthStatusManager();
  private final Server server;
  private final StandaloneQueryService queries;

  /**
   * Registers bounded standalone services and their transport security without starting listeners.
   */
  public StandaloneGrpcServer(StandaloneRuntime runtime) throws IOException {
    this.runtime = runtime;
    this.dispatcher = new RpcDispatcher(runtime.config(), runtime.metrics());
    this.writes = new DocumentWriteCoordinator(runtime);
    this.queries = new StandaloneQueryService(runtime, this.dispatcher);
    this.deadlines =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("standalone-deadlines").factory());
    this.deadlines.setRemoveOnCancelPolicy(true);
    var interceptor =
        new StandaloneAuthInterceptor(runtime.config(), this.deadlines, runtime.metrics());
    var builder =
        NettyServerBuilder.forAddress(runtime.config().address())
            .directExecutor()
            .maxInboundMessageSize(runtime.config().documentLimits().maxBatchBytes() + 65536)
            .maxConcurrentCallsPerConnection(runtime.config().maxInFlightRequests() + 16)
            .addService(ServerInterceptors.intercept(new Catalog(), interceptor))
            .addService(ServerInterceptors.intercept(new Documents(), interceptor))
            .addService(ServerInterceptors.intercept(this.queries, interceptor))
            .addService(ServerInterceptors.intercept(new Operations(), interceptor))
            .addService(ServerInterceptors.intercept(this.health.getHealthService(), interceptor));
    if (runtime.config().tls().isPresent()) {
      var tls = runtime.config().tls().get();
      builder.sslContext(
          GrpcSslContexts.forServer(tls.certificate().toFile(), tls.privateKey().toFile())
              .sslProvider(SslProvider.JDK)
              .build());
    }
    this.server = builder.build();
    this.health.setStatus("", ServingStatus.SERVING);
    this.health.setStatus(CatalogServiceGrpc.SERVICE_NAME, ServingStatus.SERVING);
    this.health.setStatus(DocumentServiceGrpc.SERVICE_NAME, ServingStatus.SERVING);
    this.health.setStatus(QueryServiceGrpc.SERVICE_NAME, ServingStatus.SERVING);
    this.health.setStatus(OperationsServiceGrpc.SERVICE_NAME, ServingStatus.SERVING);
    this.deadlines.scheduleWithFixedDelay(
        () ->
            this.health.setStatus(
                "", runtime.managementReady() ? ServingStatus.SERVING : ServingStatus.NOT_SERVING),
        1,
        1,
        TimeUnit.SECONDS);
    this.deadlines.scheduleWithFixedDelay(this.queries::expireIdle, 10, 10, TimeUnit.SECONDS);
  }

  public StandaloneGrpcServer start() throws IOException {
    this.server.start();
    return this;
  }

  public int port() {
    return this.server.getPort();
  }

  public void awaitTermination() throws InterruptedException {
    this.server.awaitTermination();
  }

  @Override
  public synchronized void close() throws InterruptedException {
    this.health.enterTerminalState();
    this.server.shutdown();
    if (!this.server.awaitTermination(30, TimeUnit.SECONDS)) {
      this.server.shutdownNow();
      this.server.awaitTermination(10, TimeUnit.SECONDS);
    }
    this.dispatcher.close();
    this.queries.close();
    this.deadlines.shutdownNow();
    this.deadlines.awaitTermination(10, TimeUnit.SECONDS);
  }

  private final class Catalog extends CatalogServiceGrpc.CatalogServiceImplBase {
    @Override
    public void createCollection(
        CreateCollectionRequest request, StreamObserver<CollectionDescription> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection("*");
            return StandaloneGrpcServer.this
                .runtime
                .ingestion()
                .coordinate(
                    () ->
                        StandaloneGrpcServer.this
                            .runtime
                            .catalog()
                            .createCollection(
                                StandaloneAuthInterceptor.principal().name(), request));
          });
    }

    @Override
    public void getCollection(
        GetCollectionRequest request, StreamObserver<CollectionDescription> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection(request.getCollectionId());
            return StandaloneGrpcServer.this
                .runtime
                .catalog()
                .getCollection(request.getCollectionId());
          });
    }

    @Override
    public void createIndex(CreateIndexRequest request, StreamObserver<IndexDescription> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection(request.getCollectionId());
            var result =
                StandaloneGrpcServer.this
                    .runtime
                    .ingestion()
                    .coordinate(
                        () ->
                            StandaloneGrpcServer.this
                                .runtime
                                .catalog()
                                .createIndex(
                                    StandaloneAuthInterceptor.principal().name(), request));
            StandaloneGrpcServer.this.runtime.catalogChanged();
            return result;
          });
    }

    @Override
    public void updateIndex(UpdateIndexRequest request, StreamObserver<IndexDescription> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection(
                StandaloneGrpcServer.this
                    .runtime
                    .catalog()
                    .getIndex(request.getIndexId())
                    .getCollectionId());
            var result =
                StandaloneGrpcServer.this
                    .runtime
                    .ingestion()
                    .coordinate(
                        () ->
                            StandaloneGrpcServer.this
                                .runtime
                                .catalog()
                                .updateIndex(
                                    StandaloneAuthInterceptor.principal().name(), request));
            StandaloneGrpcServer.this.runtime.catalogChanged();
            return result;
          });
    }

    @Override
    public void dropIndex(DropIndexRequest request, StreamObserver<IndexDescription> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection(
                StandaloneGrpcServer.this
                    .runtime
                    .catalog()
                    .getIndex(request.getIndexId())
                    .getCollectionId());
            var result =
                StandaloneGrpcServer.this
                    .runtime
                    .ingestion()
                    .coordinate(
                        () ->
                            StandaloneGrpcServer.this
                                .runtime
                                .catalog()
                                .dropIndex(StandaloneAuthInterceptor.principal().name(), request));
            StandaloneGrpcServer.this.queries.indexDropped(request.getIndexId());
            StandaloneGrpcServer.this.runtime.catalogChanged();
            return result;
          });
    }

    @Override
    public void getIndex(GetIndexRequest request, StreamObserver<IndexDescription> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            var result = StandaloneGrpcServer.this.runtime.describeIndex(request.getIndexId());
            StandaloneAuthInterceptor.requireCollection(result.getCollectionId());
            return result;
          });
    }

    @Override
    public void listIndexes(
        ListIndexesRequest request, StreamObserver<ListIndexesResponse> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection(request.getCollectionId());
            var result = StandaloneGrpcServer.this.runtime.catalog().listIndexes(request);
            return result.toBuilder()
                .clearIndexes()
                .addAllIndexes(
                    result.getIndexesList().stream()
                        .map(
                            index ->
                                StandaloneGrpcServer.this.runtime.describeIndex(index.getIndexId()))
                        .toList())
                .build();
          });
    }
  }

  private final class Operations extends OperationsServiceGrpc.OperationsServiceImplBase {
    @Override
    public void getStatus(GetStatusRequest request, StreamObserver<ServiceStatus> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection("*");
            try {
              return StandaloneGrpcServer.this.runtime.serviceStatus();
            } catch (Exception e) {
              throw io.grpc.Status.UNAVAILABLE
                  .withDescription("Cannot read local operational status")
                  .withCause(e)
                  .asRuntimeException();
            }
          });
    }
  }

  private final class Documents extends DocumentServiceGrpc.DocumentServiceImplBase {
    @Override
    public void write(WriteRequest request, StreamObserver<WriteResponse> observer) {
      StandaloneGrpcServer.this.dispatcher.async(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection(request.getCollectionId());
            return StandaloneGrpcServer.this.writes.write(
                StandaloneAuthInterceptor.principal().name(), request);
          });
    }

    @Override
    public void writeBatch(WriteBatchRequest request, StreamObserver<WriteResponse> observer) {
      StandaloneGrpcServer.this.dispatcher.async(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection(request.getCollectionId());
            return StandaloneGrpcServer.this.writes.write(
                StandaloneAuthInterceptor.principal().name(), request);
          });
    }

    @Override
    public void getWriteResult(
        GetWriteResultRequest request, StreamObserver<WriteResponse> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection(request.getCollectionId());
            return StandaloneGrpcServer.this.writes.get(
                StandaloneAuthInterceptor.principal().name(),
                request.getCollectionId(),
                request.getRequestId());
          });
    }

    @Override
    public void awaitSequence(
        AwaitSequenceRequest request, StreamObserver<IndexWatermarks> observer) {
      StandaloneGrpcServer.this.dispatcher.async(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireCollection(
                StandaloneGrpcServer.this
                    .runtime
                    .catalog()
                    .getIndex(request.getIndexId())
                    .getCollectionId());
            return StandaloneGrpcServer.this
                .runtime
                .ingestion()
                .await(
                    request.getIndexId(),
                    request.getGenerationId(),
                    request.getSequence(),
                    request.getCondition());
          });
    }
  }
}
