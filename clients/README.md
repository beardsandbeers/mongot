# Standalone mongot push clients

Java, Rust, Python and TypeScript clients share the
[protobuf API](../src/proto/standalone/v1/standalone.proto). Create an index using the normal
mongot schema, then upsert BSON directly into that index. There is no collection creation,
source document store, durable retry identity or automatic backfill. Schema updates create an
empty generation; reingest from your own source.

Versions 0.3.0 and later use the push API, which is incompatible with collection-backed 0.2.x clients.
Use matching server and client versions. Each wrapper provides TLS/bearer authentication,
per-call deadlines, BSON helpers and all current RPCs. No automatic retries are performed.

## Python

```sh
python3 -m pip install mongot-standalone-client==0.4.0
```

```python
from mongot_standalone import Client, api, encode_bson, decode_bson
import time

with Client("127.0.0.1:27029") as client:
    index = client.create_index(api.CreateIndexRequest(
        name="articles", type=api.INDEX_TYPE_SEARCH,
        definition_bson=encode_bson({"mappings": {"dynamic": True}}),
    ))
    while client.get_index(api.GetIndexRequest(index_id=index.index_id)).lifecycle_state != "READY":
        time.sleep(0.05)
    client.upsert(index.index_id, {"_id": "a", "title": "Coffee guide"})
    response = client.search(api.QueryRequest(
        index_id=index.index_id,
        query_bson=encode_bson({"text": {"path": "title", "query": "coffee"}}),
    ))
    print(decode_bson(response.batch_bson))
    client.delete(index.index_id, "a")
```

For TLS, pass `ca_file` and `token_file` to Client. Bind helpers with
`client.index(index_id)`; the result has upsert/upsert_many/delete/delete_many methods.
Use `concern="committed"` for a Lucene commit before acknowledgement. Default `visible`
refreshes the reader but does not promise crash durability. Batches can partially apply on error;
order retries against newer writes yourself. Delete returns a processed-ID count.

## Backpressure

Bound concurrent requests and back off with jitter on `RESOURCE_EXHAUSTED`. The server caps request
count and aggregate request bytes; slow synchronous Lucene work retains that capacity. There is
no persistent ingestion queue or automatic retry. A timed-out write can have applied a prefix;
order retries against newer updates. See the [server backpressure contract](../docs/standalone-mongot.md#backpressure-and-overload)
for defaults and the current shared ingestion lock.

## Other languages

- Java: `mvn -f clients/java/pom.xml package`. Generated messages are in
  `com.xgen.mongot.standalone.grpc`; `StandaloneClient` exposes typed blocking calls.
- TypeScript: run `npm ci && npm run build` in `clients/node`. `encodeBson` produces BSON bytes;
  `StandaloneClient` exposes typed Promise methods. uint64 values use decimal strings.
- Rust: `cargo build --manifest-path clients/rust/Cargo.toml`. `Client` exposes async methods;
  generated messages are in its `api` module. BSON helpers preserve native BSON types.

UpsertRequest contains `index_id`, `documents_bson`, optional `generation_id` and `write_concern`.
DeleteRequest substitutes `ids_bson`, each containing `{_id: value}`. Queries continue to use
`index_id` and BSON search/vector expressions. CreateIndex takes name, type and definition BSON;
ListIndexes takes an empty request. Index names are instance-wide.

## Export a dynamic mapping

After indexing representative documents into a search index, call `CatalogService.GetIndexMappings`
to export a static mapping. The RPC requires the `catalog` role and access to that index.
`GetIndexMappings` is available in server and client versions 0.4.0 and later:

```python
from bson.json_util import loads
from mongot_standalone import api, encode_bson

exported = client.get_index_mappings(api.GetIndexMappingsRequest(index_id=index.index_id))
print(exported.mappings_json)
static_index = client.create_index(api.CreateIndexRequest(
    name="articles-static",
    type=api.INDEX_TYPE_SEARCH,
    definition_bson=encode_bson(loads(exported.definition_json)),
))
```

`mappings_json` is the public `mappings` object. `definition_json` contains the complete public
index definition with those mappings, preserving index-level analyzers, stored source, and other
options. Both use Extended JSON. A path observed as both a number and a string becomes a list of
mapping types. Explicit fields and options are preserved even when no document uses them;
dynamic document and embedded-document fields are expanded with `dynamic: false` at every level.
Reingest your documents into the new index after it becomes ready.

The request accepts an optional `generation_id` fence. The response identifies the generation and
active revision, and the server refreshes its readers before collecting metadata from all partitions.
This is a snapshot of materialized types: deleted fields can remain until segment merges, while
null-only fields, empty objects/arrays, and unindexed values cannot supply an inferred type.
Concurrent writes can add fields after the snapshot. Vector-search indexes return
`FAILED_PRECONDITION`; unavailable or transitioning search indexes return `UNAVAILABLE`.

## Verify

```sh
bash clients/check.sh --launcher /path/to/mongot-standalone
```

This builds all clients and runs each RPC over TLS against an isolated real server. Pass
`--plaintext` for local plaintext verification. The suite checks direct writes, index isolation,
empty mapping rebuilds, cursor behavior and restart persistence. It never uses MongoDB.
Environment overrides: `MONGOT_PYTHON`, `MONGOT_MVN`, and per-language `MONGOT_*_COMMAND`.

Release packaging remains in `scripts/standalone/package_release.py`; verification installs
artifacts independently using `scripts/standalone/verify_release.py`. Neither publishes artifacts.
