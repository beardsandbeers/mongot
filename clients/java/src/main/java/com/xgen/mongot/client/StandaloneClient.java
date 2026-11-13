package com.xgen.mongot.client;

import com.google.protobuf.ByteString;
import com.xgen.mongot.standalone.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.bson.BsonDocument;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;

/**
 * All standalone RPCs. A fresh deadline is applied to every call; retries are caller-controlled.
 */
public final class StandaloneClient implements AutoCloseable {
  private final ManagedChannel channel;
  private final long timeoutNanos;
  public final CatalogServiceGrpc.CatalogServiceBlockingStub catalog;
  public final DocumentServiceGrpc.DocumentServiceBlockingStub documents;
  public final OperationsServiceGrpc.OperationsServiceBlockingStub operations;
  public final QueryServiceGrpc.QueryServiceBlockingStub queries;

  public StandaloneClient(String address, Path caFile, Path tokenFile, Duration timeout)
      throws Exception {
    if (timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("timeout must be positive");
    if (tokenFile != null && caFile == null)
      throw new IllegalArgumentException("bearer authentication requires TLS");
    this.timeoutNanos = timeout.toNanos();
    var builder = NettyChannelBuilder.forTarget(address).maxInboundMessageSize(64 * 1024 * 1024);
    if (caFile == null) builder.usePlaintext();
    else builder.sslContext(GrpcSslContexts.forClient().trustManager(caFile.toFile()).build());
    var headers = new Metadata();
    if (tokenFile != null)
      headers.put(
          Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
          "Bearer " + Files.readString(tokenFile).strip());
    channel = builder.build();
    var auth = MetadataUtils.newAttachHeadersInterceptor(headers);
    catalog = CatalogServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    documents = DocumentServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    operations = OperationsServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
    queries = QueryServiceGrpc.newBlockingStub(channel).withInterceptors(auth);
  }

  public static ByteString encodeBson(BsonDocument document) {
    var buffer = new RawBsonDocument(document, new BsonDocumentCodec()).getByteBuffer().asNIO();
    return ByteString.copyFrom(buffer);
  }

  public static BsonDocument decodeBson(ByteString bytes) {
    return new RawBsonDocument(bytes.toByteArray());
  }

  @Override
  public void close() throws InterruptedException {
    channel.shutdownNow();
    channel.awaitTermination(5, TimeUnit.SECONDS);
  }

  public CollectionDescription createCollection(CreateCollectionRequest request) {
    return catalog.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).createCollection(request);
  }

  public CollectionDescription getCollection(GetCollectionRequest request) {
    return catalog.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getCollection(request);
  }

  public IndexDescription createIndex(CreateIndexRequest request) {
    return catalog.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).createIndex(request);
  }

  public IndexDescription updateIndex(UpdateIndexRequest request) {
    return catalog.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).updateIndex(request);
  }

  public IndexDescription dropIndex(DropIndexRequest request) {
    return catalog.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).dropIndex(request);
  }

  public IndexDescription getIndex(GetIndexRequest request) {
    return catalog.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getIndex(request);
  }

  public ListIndexesResponse listIndexes(ListIndexesRequest request) {
    return catalog.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).listIndexes(request);
  }

  public WriteResponse write(WriteRequest request) {
    return documents.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).write(request);
  }

  public WriteResponse writeBatch(WriteBatchRequest request) {
    return documents.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).writeBatch(request);
  }

  public WriteResponse getWriteResult(GetWriteResultRequest request) {
    return documents.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getWriteResult(request);
  }

  public IndexWatermarks awaitSequence(AwaitSequenceRequest request) {
    return documents.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).awaitSequence(request);
  }

  public ServiceStatus getStatus(GetStatusRequest request) {
    return operations.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getStatus(request);
  }

  public QueryResponse search(QueryRequest request) {
    return queries.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).search(request);
  }

  public QueryResponse vectorSearch(QueryRequest request) {
    return queries.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).vectorSearch(request);
  }

  public QueryResponse getMore(GetMoreRequest request) {
    return queries.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getMore(request);
  }

  public KillCursorResponse killCursor(KillCursorRequest request) {
    return queries.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).killCursor(request);
  }

  public UpsertResponse upsert(UpsertRequest request) {
    return documents.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).upsert(request);
  }

  public DeleteResponse delete(DeleteRequest request) {
    return documents.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).delete(request);
  }

  public DeleteByQueryResult deleteByQuery(DeleteByQueryRequest request) {
    return documents.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).deleteByQuery(request);
  }

  public DeleteByQueryResult getDeleteByQueryResult(GetDeleteByQueryResultRequest request) {
    return documents
        .withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS)
        .getDeleteByQueryResult(request);
  }
}
