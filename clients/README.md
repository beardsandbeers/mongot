# Standalone mongot gRPC clients

Java, Rust, Python and Node/TypeScript clients cover every RPC in
[`standalone.proto`](../src/proto/standalone/v1/standalone.proto). Each package exposes typed
requests/responses, BSON encoding/decoding helpers, TLS with a supplied CA, bearer tokens read
from a file, a fresh deadline on each call, and explicit connection cleanup. No MongoDB driver
connection or running MongoDB server is needed.

| Service | Operations |
| --- | --- |
| Catalog | CreateCollection, GetCollection, CreateIndex, UpdateIndex, DropIndex, GetIndex, ListIndexes |
| Documents | Write, WriteBatch, GetWriteResult, AwaitSequence |
| Queries | Search, VectorSearch, GetMore, KillCursor |
| Operations | GetStatus |

Build from this checkout. Java, Rust and TypeScript generate bindings from the server's schema
during their builds; Python includes generated bindings and a regeneration command. The server
operating guide explains [startup and credentials](../docs/standalone-mongot.md).

## Build and run the full integration suite

Prerequisites: Python 3.10+, Java 17+ with Maven 3.9+, Node 22+ with npm, a current stable Rust
compiler/Cargo, and OpenSSL. The **server** uses the Java 21 runtime in its release archive.
Client builds download dependencies from their normal registries. No system `protoc` is required.

First build/extract the server as described in the operating guide. From the repository root:

```sh
clients/check.sh --launcher /absolute/path/to/mongot-standalone/mongot-standalone
```

The script creates `clients/python/.venv`, installs Python dependencies, builds all four packages,
and runs `clients/tests/e2e.py`. Override `MONGOT_PYTHON` or `MONGOT_MVN` to use existing tool
installations. Maven, npm and Cargo honor their usual cache settings. Build outputs are ignored.

The suite launches a **real packaged mongot process**, creates disposable TLS credentials and
isolated storage under `/tmp`, and tests every RPC through each language's native gRPC client.
It verifies strict INSERT, replacement UPSERT, idempotent DELETE, ordered partial batches,
retry outcomes, all three sequence conditions, historical index backfill, text and vector query
results, cursor paging/killing, index pagination, mapping rebuilds, revision conflicts, generation
fencing, drop/recreate behavior, BSON fidelity, and persistence across a process restart. It fails
if a schema RPC is missing from the exercised set. Logs/state remain in the printed temporary
directory for inspection; credentials are not printed. The default JSON report is
`/tmp/mongot-client-e2e-results.json`; pass `--output` to choose another path.

After building, rerun selected clients without rebuilding:

```sh
clients/python/.venv/bin/python clients/tests/e2e.py \
  --launcher /absolute/path/to/mongot-standalone/mongot-standalone \
  --languages python java node rust
```

Use `--plaintext` to test the isolated development listener. To test an **existing disposable**
server, pass `--address localhost:27029` instead of `--launcher`, set `MONGOT_CA` and
`MONGOT_TOKEN_FILE` for TLS/auth, and omit `--plaintext`. That mode creates collections/indexes
on the specified server and cannot verify process restart. The test runner accepts
`MONGOT_JAVA_COMMAND`, `MONGOT_RUST_COMMAND`, `MONGOT_NODE_COMMAND`, or `MONGOT_PYTHON_COMMAND`
to run an alternate built JSON-lines adapter.

## Python

```sh
python3 -m venv clients/python/.venv
clients/python/.venv/bin/python -m pip install -e 'clients/python[codegen]'
clients/python/.venv/bin/python clients/python/generate.py  # after changing standalone.proto
```

```python
from mongot_standalone import Client, api, encode_bson, decode_bson

with Client("localhost:27029", ca_file="server.crt", token_file="admin.token", timeout=30) as client:
    collection = client.create_collection(api.CreateCollectionRequest(
        request_id="my-stable-collection-request", database="example", name="articles"))
    index = client.create_index(api.CreateIndexRequest(
        request_id="my-stable-index-request", collection_id=collection.collection_id,
        name="text", type=api.INDEX_TYPE_SEARCH,
        definition_bson=encode_bson({"mappings": {"dynamic": True}, "storedSource": True})))
    description = client.get_index(api.GetIndexRequest(index_id=index.index_id))
```

All methods use snake_case and accept generated `api` requests. They return generated response
objects. `timeout=` can override the client deadline per call. Raw service stubs are also exposed
as `catalog`, `documents`, `queries`, and `operations` for advanced gRPC options.

## Java

```sh
mvn -f clients/java/pom.xml package
```

Use the `com.xgen.mongot.client.StandaloneClient` library and generated
`com.xgen.mongot.standalone.grpc` request classes. The Java client requires Java 17 or newer.
The shaded `clients/java/target/standalone-client-0.1.0.jar` also includes the integration adapter.

```java
import com.xgen.mongot.client.StandaloneClient;
import com.xgen.mongot.standalone.grpc.*;
import java.nio.file.Path;
import java.time.Duration;
import org.bson.BsonDocument;

try (var client = new StandaloneClient("localhost:27029", Path.of("server.crt"),
        Path.of("admin.token"), Duration.ofSeconds(30))) {
    var collection = client.createCollection(CreateCollectionRequest.newBuilder()
        .setRequestId("my-stable-collection-request").setDatabase("example").setName("articles").build());
    var index = client.createIndex(CreateIndexRequest.newBuilder()
        .setRequestId("my-stable-index-request").setCollectionId(collection.getCollectionId())
        .setName("text").setType(IndexType.INDEX_TYPE_SEARCH)
        .setDefinitionBson(StandaloneClient.encodeBson(
            BsonDocument.parse("{mappings:{dynamic:true},storedSource:true}"))).build());
}
```

Methods use lowerCamelCase. `encodeBson`/`decodeBson` use `BsonDocument` and protobuf `ByteString`.
The four public blocking service stubs allow custom interceptors and per-call options.

## Node / TypeScript

```sh
cd clients/node
npm install
npm run build
```

```typescript
import { StandaloneClient, encodeBson, decodeBson } from './dist/client';

const client = new StandaloneClient({ address: 'localhost:27029',
  caFile: 'server.crt', tokenFile: 'admin.token', timeoutMs: 30000 });
try {
  const collection = await client.createCollection({ requestId: 'my-stable-collection-request',
    database: 'example', name: 'articles' });
  const index = await client.createIndex({ requestId: 'my-stable-index-request',
    collectionId: collection.collectionId, name: 'text', type: 'INDEX_TYPE_SEARCH',
    definitionBson: encodeBson({ mappings: { dynamic: true }, storedSource: true }) });
} finally { client.close(); }
```

JavaScript uses the same API with `require('./dist/client')`. Methods return Promises and expose
native gRPC errors with numeric `code` and `details`. **uint64 values, including cursor IDs and
sequences, are decimal strings**; never convert large IDs to JavaScript `number`. BSON `Long`,
ObjectId, dates and binary values can be supplied using the `bson` package. `decodeBson` preserves
BSON int64 as `Long`. Generated TypeScript types live under `dist/generated` after a build.

## Rust

```sh
cargo build --manifest-path clients/rust/Cargo.toml
```

Use the `mongot-standalone-client` crate with Tokio. All generated messages, enums, and service
clients are in `api`; RPC methods use snake_case and return `Result<_, tonic::Status>`.

```rust
use mongot_standalone_client::{api::*, bson::doc, encode_bson, Client, ClientOptions};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
let mut client = Client::connect(ClientOptions {
    address: "localhost:27029".into(),
    ca_file: Some("server.crt".into()), token_file: Some("admin.token".into()),
    ..ClientOptions::default()
}).await?;
let collection = client.create_collection(CreateCollectionRequest {
    request_id: "my-stable-collection-request".into(), database: "example".into(), name: "articles".into(),
}).await?;
let index = client.create_index(CreateIndexRequest {
    request_id: "my-stable-index-request".into(), collection_id: collection.collection_id,
    name: "text".into(), r#type: IndexType::Search as i32,
    definition_bson: encode_bson(&doc! { "mappings": { "dynamic": true }, "storedSource": true })?,
}).await?;
Ok(())
}
```

Dropping the client closes its channels when no clones remain. Sequence/revision/cursor fields
use `u64`. `encode_bson`/`decode_bson` preserve BSON types. `api` messages implement serde with
canonical protobuf JSON (base64 bytes, string uint64s, named enums).
The wrapper maps Tonic's local timeout error to `DEADLINE_EXCEEDED`, preserving other cancellations.

## Write, revision, visibility and retry contract

- Wait for an index's `lifecycle_state == READY` and its expected `active_revision` before writing.
  This build requires a live ready index for writes. Catalog mutations acknowledge desired state;
  callers poll GetIndex to observe readiness or a nonempty failure message.
  Drop prevents new queries and invalidates existing cursors before acknowledging the change;
  background reconciliation retires the old physical generation.
- Write/WriteBatch return per-item results. Check `complete`, each `state == INDEXED`, and each
  `status_code == 0`. Transport OK alone does not mean every mutation succeeded. INSERT rejects
  duplicate IDs; UPSERT replaces the full document; DELETE takes exactly `{_id: value}`.
- Keep the **same request ID and identical payload** when retrying a write or catalog operation.
  Clients do not automatically replay mutations. On an ambiguous deadline/transport failure,
  call GetWriteResult or resubmit the identical request within the server's retry-retention window.
- UpdateIndex/DropIndex require the last `desired_revision` as `expected_revision`. On a conflict,
  inspect current state and decide whether a new operation is appropriate; do not blindly retry
  with a changed revision. Mapping updates replace the full definition.
- `INDEXED` is distinct from reader visibility/durability. AwaitSequence takes the exact generation
  ID from GetIndex.live, the accepted sequence, and APPLIED, COMMITTED or VISIBLE. Pin query
  `generation_id` when an operation must reject a generation change.
- Search/VectorSearch accept BSON expression bodies; query results are BSON `{results: [...]}`.
  Decode `batch_bson`. A nonzero cursor requires GetMore or KillCursor. ListIndexes pagination
  uses `next_page_token`. Dropping an index leaves source documents available for a new index.

For local unauthenticated development only, omit both CA and token file. A token requires TLS in
these wrappers. No client disables hostname/certificate validation. Each RPC defaults to a
30-second client deadline, additionally bounded by the server's configured maximum.
