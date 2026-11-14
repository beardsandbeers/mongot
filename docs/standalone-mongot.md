# Running mongot in push mode

The standalone executable uses mongot's ordinary Lucene indexes, BSON mapping validation,
text/vector readers, and cursor manager. It does not connect to MongoDB. It stores content
only through Lucene, including `storedSource` when explicitly configured in the index schema.

## Build and run

```sh
make build.deploy.standalone
tar -xzf bazel-bin/deploy/mongot-standalone.tgz -C /tmp
cd /tmp/mongot-standalone
./mongot-standalone --config standalone.yaml
```

The archive includes Java 21, native libraries, the API schema, and an example configuration.
Docker build and TLS bootstrap instructions are in [the container guide](../deploy/standalone/DOCKERHUB.md).

## Configuration

```yaml
mode: standalone
storage:
  dataPath: ./data
server:
  grpc:
    address: 127.0.0.1:27029
ingestion:
  maxInFlightRequests: 64
  maxDocumentBytes: 16777216
  maxNestingDepth: 100
  maxBatchDocuments: 1000
  maxBatchBytes: 33554432
  maxQueuedBytes: 268435456
  minFreeDiskBytes: 268435456
  commitIntervalMillis: 1000
querying:
  refreshIntervalMillis: 1000
```

Non-loopback listeners require TLS and bearer authentication. Paths resolve relative to the YAML file.
Unknown and duplicate configuration keys are rejected. Configure index grants by ObjectId hex ID:

```yaml
server:
  grpc:
    address: 0.0.0.0:27029
    tls:
      certificate: server.crt
      privateKey: server.key
    principals:
      - name: admin
        tokenFile: admin.token
        roles: [catalog, write, query]
        indexes: ['*']
      - name: reader
        tokenFile: reader.token
        roles: [query]
        indexes: ['REPLACE_WITH_INDEX_ID']
```

Tokens contain 32–4096 non-whitespace ASCII characters. Creation and instance status require a
wildcard grant. Catalog, write, and query roles are separate. Cursor access is scoped to the
principal and index. RPC concurrency, aggregate queued bytes, deadlines, batch sizes and cursor
count are bounded. Disk admission checks free space for Lucene.

## Backpressure and overload

Admission is bounded in memory. Defaults allow 64 running or queued dispatched RPCs and
256 MiB of aggregate serialized request payloads, using up to eight shared workers and a bounded
worker queue. These are request accounting limits, not a total JVM memory limit: protobuf decoding,
BSON conversion, Lucene buffers and responses also consume memory. Transport admission additionally
limits concurrent calls to `maxInFlightRequests + 16` and caps each inbound message at
`maxBatchBytes + 65536` bytes.

When the request-count, payload-byte, transport-call or worker-queue limit fills, new calls fail
with gRPC `RESOURCE_EXHAUSTED`. Disk headroom exhaustion also rejects writes with that status.
Use bounded client concurrency and exponential backoff with jitter. Clients perform no automatic
retries; preserve ordering against newer writes when retrying replacements or deletions.

Indexing runs synchronously on the RPC workers. Slow Lucene writes hold admission capacity until
they finish, so the limits eventually reject excess traffic instead of growing a persistent queue.
Writes and commits currently share one ingestion lock across all indexes. A slow write can delay
other indexes and occupy shared workers; there is no adaptive or per-index rate limiter. Client
and server deadlines cancel cooperatively between documents and before queued work starts; they
do not interrupt a Lucene call already in progress.

GetStatus exposes the `standalone.rpc.inflight`, `standalone.rpc.bytes`, `standalone.rpc.queued`
and `standalone.rpc.rejected` metrics. The release checks cover functional RPC behavior, TLS,
index authorization and committed crash recovery. They are not a sustained saturation benchmark.

## API

The authoritative [protobuf schema](../src/proto/standalone/v1/standalone.proto) defines:

| Service | Operations |
| --- | --- |
| CatalogService | CreateIndex, GetIndex, GetIndexMappings, GetIndexStats, GetIndexFieldStats, ListIndexes, UpdateIndex, DropIndex |
| DocumentService | Upsert, Delete |
| QueryService | Search, VectorSearch, GetMore, KillCursor |
| OperationsService | GetStatus |

Create an index by name, explicit type (`SEARCH` or `VECTOR_SEARCH`), and user mapping BSON.
Poll GetIndex until `READY`. BSON uses the ordinary mongot schema, such as
`{mappings: {dynamic: true}}` for text or
`{fields: [{type: 'vector', path: 'embedding', numDimensions: 3, similarity: 'cosine'}]}` for vectors.
An equivalent CreateIndex with the same name returns the existing index; a conflicting definition
fails. Index names are instance-wide. Up to 64 indexes are supported.

`GetIndexMappings` exports a ready search index's configured mappings merged with dynamic types
found in Lucene metadata, with `dynamic: false` at every document level. It returns the mappings
and a complete reusable public index definition as Extended JSON, along with the generation and
active revision. Readers are refreshed before the snapshot. The `catalog` role and index access
are required. See [the mapping export example](../clients/README.md#export-a-dynamic-mapping) for
creating a static index and the limits of metadata-based inference.

Upsert carries an `index_id` and an ordered batch of complete BSON documents. Every document
requires an `_id` (string, ObjectId, int32 or int64). Replacing the same ID removes the previous
indexed fields; omitted fields disappear. Delete carries an `index_id` and BSON wrappers
`{_id: value}`. Missing IDs succeed; the response counts processed IDs, not prior existence.
BSON ID widths are preserved. Invalid BSON, duplicate field names, unsupported IDs, excessive
nesting and IDs exceeding Lucene's term limit fail validation before the batch is applied.
Existing mongot mapping rules determine which fields produce indexed values; a successful write
does not guarantee that a mismatched field value was indexed.

A write targets exactly one index. For multiple text/vector indexes, the caller submits content
to each index. There is no fan-out, cross-index transaction, collection, document read API,
strict INSERT, delete-by-query workflow, request-ID deduplication, or replay endpoint.

### Join filters

Server 0.6.0 adds cross-index and self-join predicates to Search BSON. For example:

```json
{
  "compound": {
    "filter": [{"join": {
      "from": "accounts_search",
      "localField": "accountKey",
      "foreignField": "accountKey",
      "operator": {"equals": {"path": "region", "value": "Berlin"}}
    }}]
  }
}
```

The request's `index_id` selects the returned documents. `from` is an exact index name;
omit it for a self-join. Place the predicate in `compound.mustNot` for exclusion.
Both keys must be string fields explicitly mapped as `token` with matching normalizers.
Arrays match on any shared indexed string. Native typed `_id` keys, nested joins,
vector joins, and attaching foreign documents are outside this release.

QUERY access is required to every participating index. Cursors retain their initial
membership and local reader snapshots; dropping a dependency or retiring its generation
invalidates them. A join fails with `RESOURCE_EXHAUSTED` if it exceeds its key, memory,
or work budgets. See the [join guide](join-design-guide.md) for mappings, full syntax,
limits, and token-length caveats; the guide also ships beside this file in the server archive.

### Completion and retries

- `VISIBLE` (default): applies the batch and refreshes the Lucene reader. Fresh searches see it.
- `INDEXED`: applies to the writer. Refresh and commit occur separately.
- `COMMITTED`: commits to Lucene before returning. Visibility follows reader refresh.

Background commits run at `commitIntervalMillis`. Shutdown commits outstanding writes before
closing indexes. Uncommitted writes can be lost on a crash; replay them from your own source.
`VISIBLE` does not imply durability. Use `COMMITTED` for crash persistence.

Batches are ordered but not atomic. A failure, deadline, or disconnect can leave a prefix applied.
Repeating a replacement/delete is idempotent in isolation, but there is no durable request ledger:
callers must order retries so an old retry cannot overwrite a newer update. Optional
`generation_id` fences writes against schema changes. Requests during a mapping transition fail
until the requested schema is ready.

### Mapping changes and index lifecycle

UpdateIndex requires the current `expected_revision`. It replaces the index with a new empty
Lucene generation. Wait for the new revision to be READY and reingest all desired content.
The previous generation is retired; no source snapshot or automatic backfill exists. Even
resubmitting the same mapping through UpdateIndex is an explicit empty rebuild.

DropIndex removes the definition and its Lucene data through the ordinary mongot lifecycle.
A later index with the same name receives a new ID and starts empty. Physical cleanup runs
asynchronously. Query cursors pin their reader snapshot; index drop invalidates those cursors.

Search and VectorSearch execute ordinary BSON search expressions against `index_id`; use
GetMore for nonzero cursor IDs and KillCursor to release them early. Results contain IDs/scores
and optional configured Lucene stored source. There is no external document hydration.

## Persistence and upgrades

Persistent state consists of Lucene directories, `indexes.json` containing index definitions,
the existing mongot configuration journal/feature flags, and a directory lock. No application
source documents or mutation payloads are written outside Lucene. Only one process may own a
data directory. Copy the full directory while stopped for backups.

The push-only API and state layout are incompatible with collection-backed 0.2.x releases.
Use a new data directory, recreate index definitions, and reingest from your source. Startup
rejects a legacy document-store directory. It does not migrate or delete legacy data.
Release 0.5.0 uses this push-only API. Use matching server and client packages;
0.2.x images and clients implement the older protocol.

MongoDB synonym sources, views, automatic embedding and custom vector engines are unsupported.
Text indexes, explicit client-provided vectors, partitions, analyzers, sort, and configured
Lucene stored source use the existing mongot implementation.
