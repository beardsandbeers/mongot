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
python3 -m pip install mongot-standalone-client==0.6.0
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

## Join searches across indexes

Join support is available in server/client release 0.6.0 and later. Existing SDKs
carry the expression through `QueryRequest.query_bson`.
A join filters local documents by the existence of qualifying foreign documents.

For example, assume `accounts_search` and `contacts_search` are ready, both map
`accountKey` as `token` with the same normalizer, accounts map `region` as `token`,
and contacts map `title` as `string`. Configure `storedSource` on contacts to use
`returnStoredSource` below:

```python
query = {
    "compound": {
        "must": [{"text": {"path": "title", "query": "architect"}}],
        "filter": [{
            "join": {
                "from": "accounts_search",
                "localField": "accountKey",
                "foreignField": "accountKey",
                "operator": {"equals": {"path": "region", "value": "Berlin"}},
            }
        }],
    },
    "returnStoredSource": True,
}
response = client.search(api.QueryRequest(
    index_id=contacts_index_id, query_bson=encode_bson(query), batch_size=100,
))
hits = decode_bson(response.batch_bson)["results"]
```

`from` is an instance-wide index name; omit it for a self-join. Use the predicate
in `compound.mustNot` for exclusion. The caller needs QUERY access to every index
in the join. Foreign matches do not duplicate local hits or affect their scores.
Arrays match when any indexed token is shared. Native typed `_id` joins and
foreign-document enrichment are outside the initial scope.

`GetMore` retains the initial local readers and foreign membership. Dropping a
dependency or retiring its generation invalidates the cursor. Budget violations
fail with `RESOURCE_EXHAUSTED` rather than returning a truncated key set. See the
[join guide](../docs/join-design-guide.md) for mappings, limits, and lifecycle details.

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

## Index and field statistics

Server and client versions 0.5.0 and later provide `CatalogService.GetIndexStats` and
`CatalogService.GetIndexFieldStats`. Both require the `catalog` role and a grant for the requested
index. They work for search and vector-search indexes. `GetStatus` still requires wildcard access.

```python
stats = client.get_index_stats(api.GetIndexStatsRequest(
    index_id=index.index_id, refresh=True,
))
print(stats.document_count, stats.index_size_bytes)
if stats.HasField("average_index_bytes_per_document"):
    print(stats.average_index_bytes_per_document)

request = api.GetIndexFieldStatsRequest(index_id=index.index_id, page_size=100)
while True:
    page = client.get_index_field_stats(request)
    if page.state == api.STATS_COLLECTION_STATE_COLLECTING:
        time.sleep(page.retry_after_millis / 1000)
        continue
    for field in page.fields:
        measured = field.attributed_disk_bytes
        print(field.path, measured.value if measured.HasField("value") else None)
    if not page.next_page_token:
        break
    request.page_token = page.next_page_token
```

Typed equivalents are `client.getIndexStats(request)` / `getIndexFieldStats(request)` in Java
and TypeScript, and `client.get_index_stats(request).await?` / `get_index_field_stats(request).await?`
in Rust. Generated request/response types use the same RPC names. TypeScript counts and byte
values are decimal strings, including `ByteMeasurement.value` when present.

`document_count` counts live source/root documents; `lucene_document_count` also includes embedded
children. `index_size_bytes` counts files referenced by the pinned searchable snapshot.
`directory_size_bytes` is a separate `ByteMeasurement` including merge/retention files; its sample
is not atomic with the snapshot. Empty-index averages are absent. File lengths differ from
filesystem allocated blocks and incremental storage cost.

The field response includes its matching `index_stats` and snapshot-wide attributed/shared/
unattributed totals. These reconcile exactly with that summary's `index_size_bytes`. Filters
(`paths=["title"]`) and pages select rows without changing global totals or coverage. Exclusive
codec ranges are attributed; shared postings and compressed stored source are kept in named
buckets. Each representation preserves its mapping type, multi-field name and embedded scope.

Memory domains are separate: accounted reader heap, writer buffers, caches, and desired vector
off-heap working set. Per-field heap is available only where ownership can be established. These
are partial accounting and capacity measurements, not process RSS or guaranteed removal savings.
Always inspect `method`, `completeness`, and included/omitted components. Missing `value` means
unavailable; present zero means a supported measurement returned zero.

Summary caches last five seconds and field caches sixty seconds. `refresh=True` starts/coalesces
a bounded collection and can refresh readers; it does not force a commit, merge, or data scan.
Poll field collections without repeatedly setting refresh. When cached results are returned,
`refresh_in_progress` and summary `age_millis` describe freshness. All pages bind to one immutable
snapshot, generation, principal, filters and page size and expire after two minutes (or eviction).
Do not set refresh on subsequent pages; repeat the same filters and page size. Supply an optional
`generation_id` fence when a generation replacement should fail the request.

Limits return `RESOURCE_EXHAUSTED`; reduce selected paths or retry queue admission with backoff.
Expired snapshots return `FAILED_PRECONDITION`, requiring a fresh first page. See the
[design and attribution rules](../docs/standalone-index-stats-design.md) for supported components.

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
