package com.xgen.mongot.standalone.server;

import com.xgen.mongot.standalone.grpc.CatalogServiceGrpc;
import com.xgen.mongot.standalone.grpc.CreateIndexRequest;
import com.xgen.mongot.standalone.grpc.DeleteRequest;
import com.xgen.mongot.standalone.grpc.DeleteResponse;
import com.xgen.mongot.standalone.grpc.DocumentServiceGrpc;
import com.xgen.mongot.standalone.grpc.DropIndexRequest;
import com.xgen.mongot.standalone.grpc.GetIndexMappingsRequest;
import com.xgen.mongot.standalone.grpc.GetIndexMappingsResponse;
import com.xgen.mongot.standalone.grpc.GetIndexRequest;
import com.xgen.mongot.standalone.grpc.GetStatusRequest;
import com.xgen.mongot.standalone.grpc.IndexDescription;
import com.xgen.mongot.standalone.grpc.ListIndexesRequest;
import com.xgen.mongot.standalone.grpc.ListIndexesResponse;
import com.xgen.mongot.standalone.grpc.OperationsServiceGrpc;
import com.xgen.mongot.standalone.grpc.QueryServiceGrpc;
import com.xgen.mongot.standalone.grpc.ServiceStatus;
import com.xgen.mongot.standalone.grpc.UpdateIndexRequest;
import com.xgen.mongot.standalone.grpc.UpsertRequest;
import com.xgen.mongot.standalone.grpc.UpsertResponse;
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
    public void createIndex(CreateIndexRequest request, StreamObserver<IndexDescription> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireIndex("*");
            var result =
                StandaloneGrpcServer.this
                    .runtime
                    .ingestion()
                    .coordinate(
                        () -> StandaloneGrpcServer.this.runtime.catalog().createIndex(request));
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
            StandaloneAuthInterceptor.requireIndex(request.getIndexId());
            var result =
                StandaloneGrpcServer.this
                    .runtime
                    .ingestion()
                    .coordinate(
                        () -> StandaloneGrpcServer.this.runtime.catalog().updateIndex(request));
            StandaloneGrpcServer.this.queries.indexDropped(request.getIndexId());
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
            StandaloneAuthInterceptor.requireIndex(request.getIndexId());
            var result =
                StandaloneGrpcServer.this
                    .runtime
                    .ingestion()
                    .coordinate(
                        () -> StandaloneGrpcServer.this.runtime.catalog().dropIndex(request));
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
            StandaloneAuthInterceptor.requireIndex(request.getIndexId());
            return StandaloneGrpcServer.this.runtime.describeIndex(request.getIndexId());
          });
    }

    @Override
    public void getIndexMappings(
        GetIndexMappingsRequest request, StreamObserver<GetIndexMappingsResponse> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireIndex(request.getIndexId());
            return StandaloneGrpcServer.this.runtime.getIndexMappings(request);
          });
    }

    @Override
    public void listIndexes(
        ListIndexesRequest request, StreamObserver<ListIndexesResponse> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            var result = ListIndexesResponse.newBuilder();
            for (var definition : StandaloneGrpcServer.this.runtime.catalog().definitions()) {
              String id = definition.getIndexId().toHexString();
              if (StandaloneAuthInterceptor.principal().allows(id)) {
                result.addIndexes(StandaloneGrpcServer.this.runtime.describeIndex(id));
              }
            }
            return result.build();
          });
    }
  }

  private final class Documents extends DocumentServiceGrpc.DocumentServiceImplBase {
    @Override
    public void upsert(UpsertRequest request, StreamObserver<UpsertResponse> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireIndex(request.getIndexId());
            return StandaloneGrpcServer.this
                .runtime
                .ingestion()
                .coordinate(
                    () -> {
                      var index =
                          StandaloneGrpcServer.this
                              .runtime
                              .catalog()
                              .getIndex(request.getIndexId());
                      StandaloneGrpcServer.this.runtime.checkWriteAdmission(
                          request.getSerializedSize());
                      String generation =
                          StandaloneGrpcServer.this
                              .runtime
                              .ingestion()
                              .write(
                                  request.getIndexId(),
                                  index.getDesiredRevision(),
                                  request.getGenerationId(),
                                  request.getDocumentsBsonList(),
                                  false,
                                  request.getWriteConcern());
                      return UpsertResponse.newBuilder()
                          .setIndexId(request.getIndexId())
                          .setGenerationId(generation)
                          .setIndexedCount(request.getDocumentsBsonCount())
                          .build();
                    });
          });
    }

    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> observer) {
      StandaloneGrpcServer.this.dispatcher.unary(
          request.getSerializedSize(),
          observer,
          () -> {
            StandaloneAuthInterceptor.requireIndex(request.getIndexId());
            return StandaloneGrpcServer.this
                .runtime
                .ingestion()
                .coordinate(
                    () -> {
                      var index =
                          StandaloneGrpcServer.this
                              .runtime
                              .catalog()
                              .getIndex(request.getIndexId());
                      StandaloneGrpcServer.this.runtime.checkWriteAdmission(
                          request.getSerializedSize());
                      String generation =
                          StandaloneGrpcServer.this
                              .runtime
                              .ingestion()
                              .write(
                                  request.getIndexId(),
                                  index.getDesiredRevision(),
                                  request.getGenerationId(),
                                  request.getIdsBsonList(),
                                  true,
                                  request.getWriteConcern());
                      return DeleteResponse.newBuilder()
                          .setIndexId(request.getIndexId())
                          .setGenerationId(generation)
                          .setProcessedCount(request.getIdsBsonCount())
                          .build();
                    });
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
            StandaloneAuthInterceptor.requireIndex("*");
            try {
              return StandaloneGrpcServer.this.runtime.serviceStatus();
            } catch (Exception e) {
              throw new java.util.concurrent.CompletionException(e);
            }
          });
    }
  }
}
