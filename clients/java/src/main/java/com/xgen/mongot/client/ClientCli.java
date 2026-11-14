package com.xgen.mongot.client;

import com.google.gson.*;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.xgen.mongot.standalone.grpc.*;
import io.grpc.StatusRuntimeException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;

/** JSON-lines integration adapter using canonical protobuf JSON. */
public final class ClientCli {
  private static Path path(String name) {
    var s = System.getenv(name);
    return s == null || s.isEmpty() ? null : Path.of(s);
  }

  public static void main(String[] args) throws Exception {
    try (var client =
            new StandaloneClient(
                System.getenv().getOrDefault("MONGOT_ADDRESS", "127.0.0.1:27029"),
                path("MONGOT_CA"),
                path("MONGOT_TOKEN_FILE"),
                Duration.ofMillis(
                    (long)
                        (Double.parseDouble(System.getenv().getOrDefault("MONGOT_TIMEOUT", "30"))
                            * 1000)));
        var input = new BufferedReader(new InputStreamReader(System.in))) {
      String line;
      while ((line = input.readLine()) != null) {
        var output = new JsonObject();
        try {
          var call = JsonParser.parseString(line).getAsJsonObject();
          var request = call.has("request") ? call.get("request").toString() : "{}";
          Message response;
          switch (call.get("method").getAsString()) {
            case "CreateIndex" -> {
              var b = CreateIndexRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.createIndex(b.build());
            }
            case "UpdateIndex" -> {
              var b = UpdateIndexRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.updateIndex(b.build());
            }
            case "DropIndex" -> {
              var b = DropIndexRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.dropIndex(b.build());
            }
            case "GetIndex" -> {
              var b = GetIndexRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.getIndex(b.build());
            }
            case "ListIndexes" -> {
              var b = ListIndexesRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.listIndexes(b.build());
            }
            case "GetStatus" -> {
              var b = GetStatusRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.getStatus(b.build());
            }
            case "Search" -> {
              var b = QueryRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.search(b.build());
            }
            case "VectorSearch" -> {
              var b = QueryRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.vectorSearch(b.build());
            }
            case "GetMore" -> {
              var b = GetMoreRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.getMore(b.build());
            }
            case "KillCursor" -> {
              var b = KillCursorRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.killCursor(b.build());
            }
            case "Upsert" -> {
              var b = UpsertRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.upsert(b.build());
            }
            case "Delete" -> {
              var b = DeleteRequest.newBuilder();
              JsonFormat.parser().merge(request, b);
              response = client.delete(b.build());
            }
            default -> throw new IllegalArgumentException("Unknown method");
          }
          output.add("response", JsonParser.parseString(JsonFormat.printer().print(response)));
        } catch (Exception error) {
          var detail = new JsonObject();
          detail.addProperty(
              "code",
              error instanceof StatusRuntimeException s
                  ? s.getStatus().getCode().name()
                  : "CLIENT_ERROR");
          detail.addProperty("message", error.getMessage());
          output.add("error", detail);
        }
        System.out.println(output);
      }
    }
  }
}
