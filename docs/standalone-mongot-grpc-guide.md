# Turning mongot into a standalone, API-driven search process

## Contents

- [Purpose and recommendation](#1-purpose-and-recommendation)
- [Existing code and MongoDB dependencies](#2-what-already-exists-and-where-mongodb-enters)
- [Target architecture](#3-target-architecture)
- [Synchronous single/batch write contract](#4-define-the-write-contract-before-implementing-the-rpc)
- [Proposed gRPC API](#5-proposed-grpc-surface)
- [Persistence, checkpoints, and recovery](#6-local-persistence-and-durability)
- [API ingestion lifecycle](#7-replace-replication-with-api-ingestion-lifecycle)
- [Local index management](#8-local-index-management-and-reconciliation)
- [Mapping builds and updates](#9-creating-and-updating-mappings-without-mongodb)
- [Startup, health, and shutdown](#10-standalone-startup-health-and-shutdown)
- [Standalone query boundary](#11-querying-without-mongod)
- [Feature compatibility](#12-features-that-otherwise-reintroduce-mongodb)
- [Security and blocking backpressure](#13-security-overload-control-and-observability)
- [Change map](#14-suggested-change-map)
- [Implementation phases](#15-staged-implementation-plan)
- [Validation plan](#16-validation-plan)
- [Prototype and completion checklist](#17-minimal-prototype-versus-completion-criteria)

## 1. Purpose and recommendation

This guide describes how to modify this repository so that one mongot process can:

- Start and remain healthy with **no mongod or mongos**.
- Create, inspect, update, and drop search/vector index definitions through gRPC.
- Accept document **INSERT**, full-document **UPSERT**, and **DELETE-by-ID** operations through gRPC.
- Persist its definitions and acknowledged documents locally, recover after a crash, and rebuild indexes without fetching anything from MongoDB.
- Continue using mongot's BSON ingestion, analyzers, mappings, Lucene indexes, query implementation, and cursor machinery.

**This is an implementation guide, not documentation for an existing standalone mode.** All new API names, configuration options, storage schemas, and classes below are proposals. The current community executable requires a MongoDB sync source. The analysis is based on repository commit `70116297003f2423f9dfcf3eeb984c6f8d70c82b`.

The recommended design is:

1. Add a separate standalone bootstrap/configuration path; keep the existing MongoDB-backed path working.
2. Add ordinary protobuf/gRPC management and ingestion services, rather than requiring clients to speak the internal MongoDB command protocol.
3. Keep a **local authoritative catalog**, **full source-document store**, **ordered mutation log**, and **retry ledger** in an embedded transactional database.
4. Treat Lucene indexes as locally rebuildable projections of that source store.
5. Replace MongoDB replication with an API-ingestion lifecycle component that owns replay, ordering, commits, index readiness, and shutdown.
6. Expose single-document and batch write RPCs that **block the client until the requested mutations have been indexed**. Use bounded in-flight work rather than an asynchronous acceptance-only API as the primary backpressure mechanism.

Merely adding an RPC that calls `IndexWriter.updateIndex()` is a useful experiment, but not a complete implementation. Without replacing the surrounding control plane, it leaves MongoDB dependencies, ambiguous acknowledgements, incomplete mapping rebuilds, and restart problems.

### Initial scope

For the first supported version, use a single process, a local persistent data directory, Lucene-backed text indexes, and Lucene-native vector indexes with client-provided vectors. Full document replacement is supported; MongoDB update operators, transactions across documents, sharding, clustering, and automatic embeddings are not required.

There are two independent scope decisions:

| Decision | Smallest useful prototype | Recommended standalone service |
| --- | --- | --- |
| Write target | An individual index | A logical collection, with fan-out to its indexes |
| Original documents | Kept by the caller | Retained locally by mongot |
| INSERT | Alias for upsert, explicitly documented | Strict create-if-absent |
| Crash recovery | Commit before acknowledgement, caller resubmits uncertain writes | Durable local log and replay |
| Write completion | Synchronous indexing plus audited commit | Durable source acceptance plus synchronous indexing |
| New/replacement mappings | Caller reloads every document | Local snapshot plus mutation-log catch-up |
| Result documents | IDs/scores, or configured stored source | Stored source or explicitly defined local hydration |

The rest of this guide describes the recommended design and calls out where a smaller prototype can stop.

## 2. What already exists and where MongoDB enters

### 2.1 Current data flow

```text
Index management request
  -> gRPC / command registry / Aic* management command
  -> authoritative definitions in MongoDB
  -> CommunityConfigUpdater polls desired definitions
  -> DefaultConfigManager reconciles the full desired catalog
  -> lifecycle initializes main/staged index generations
  -> MongoDB initial collection scan, then change-stream replication
  -> DocumentEvent -> IndexWriter -> Lucene

Status and readiness
  -> local index state plus MongoDB-backed metadata/stats/server state
```

Important source entry points:

| Area | Existing implementation and relevance |
| --- | --- |
| Executable | [`MongotCommunity`](../src/main/java/com/xgen/mongot/community/MongotCommunity.java): initializes logging, security providers, vector native support, feature flags, and requires Java 21. |
| Composition root | [`OnPremMongotBootstrapper`](../src/main/java/com/xgen/mongot/config/provider/community/OnPremMongotBootstrapper.java): constructs sync-source discovery, metadata clients, topology monitoring, metadata service, replication/lifecycle, config polling, gRPC, and readiness. |
| Required sync source | [`CommunityConfig`](../src/main/java/com/xgen/mongot/config/provider/community/CommunityConfig.java): `syncSource` is required; omitting it is not an existing standalone switch. |
| Authoritative definitions | [`catalogservice/README.md`](../src/main/java/com/xgen/mongot/catalogservice/README.md), [`DefaultMetadataService`](../src/main/java/com/xgen/mongot/catalogservice/DefaultMetadataService.java): definitions, per-server stats, and server state are stored in MongoDB's `__mdb_internal_search` database. |
| Desired-state polling | [`CommunityConfigUpdater.update()`](../src/main/java/com/xgen/mongot/config/provider/community/CommunityConfigUpdater.java): refreshes MongoDB sync sources/topology/views, reads authoritative definitions, then calls `ConfigManager.update()`. |
| Index reconciliation | [`DefaultConfigManager`](../src/main/java/com/xgen/mongot/config/manager/DefaultConfigManager.java): restores the local config journal, creates generations, handles staged replacements, recovery, and retiring generations. |
| User mapping parsing | [`UserIndexDefinition`](../src/main/java/com/xgen/mongot/server/command/management/definition/common/UserIndexDefinition.java), [`UserSearchIndexDefinition`](../src/main/java/com/xgen/mongot/server/command/management/definition/common/UserSearchIndexDefinition.java), [`UserVectorIndexDefinition`](../src/main/java/com/xgen/mongot/server/command/management/definition/common/UserVectorIndexDefinition.java). |
| Definition conversion | [`IndexMapper.toInternal()`](../src/main/java/com/xgen/mongot/server/command/management/util/IndexMapper.java): builds internal definitions from user mappings and caller-supplied collection identity. This conversion itself does not need MongoDB. |
| Management validation | [`AicCreateSearchIndexesCommand`](../src/main/java/com/xgen/mongot/server/command/management/aic/AicCreateSearchIndexesCommand.java): useful definition/analyzer/invariant checks, but also a MongoDB topology guard and MongoDB-backed catalog writes. |
| Lifecycle | [`DefaultLifecycleManager`](../src/main/java/com/xgen/mongot/lifecycle/DefaultLifecycleManager.java), [`IndexLifecycleManager`](../src/main/java/com/xgen/mongot/lifecycle/IndexLifecycleManager.java): initialize physical indexes, register initialized generations, then attach replication. |
| Mutation abstraction | [`DocumentEvent`](../src/main/java/com/xgen/mongot/index/DocumentEvent.java), [`IndexWriter`](../src/main/java/com/xgen/mongot/index/IndexWriter.java): engine-facing insert/update/delete and commit interfaces. |
| Lucene mutation implementation | [`SingleLuceneIndexWriter`](../src/main/java/com/xgen/mongot/index/lucene/writer/SingleLuceneIndexWriter.java), [`MultiLuceneIndexWriter`](../src/main/java/com/xgen/mongot/index/lucene/writer/MultiLuceneIndexWriter.java). |
| Existing gRPC transport | [`GrpcStreamingServer`](../src/main/java/com/xgen/mongot/server/grpc/GrpcStreamingServer.java), [`CommandStreamMethods`](../src/main/java/com/xgen/mongot/server/grpc/CommandStreamMethods.java): Netty, TLS, health, internal command streams, and unary index management. |

### 2.2 The core writer is already close to what is needed

`SingleLuceneIndexWriter.updateIndex()` dispatches as follows:

```text
DocumentEvent.DELETE          -> deleteDocuments(encoded _id term)
DocumentEvent.INSERT/UPDATE   -> build Lucene document block
                             -> updateDocuments(encoded _id term, block)
```

Both INSERT and UPDATE already replace existing documents with the same encoded `_id`. This is intentional: replication can replay previously indexed documents.

Consequences:

- Full-document UPSERT fits the existing writer directly.
- DELETE-by-ID fits directly and is naturally idempotent.
- A `DocumentEvent.INSERT` is **not** a uniqueness-enforcing insert. Strict INSERT must check/create the ID in the source store before emitting an event.
- UPDATE here is a replacement document, not a `$set`/`$inc` update language for a standalone client.
- Keep using the document-block builder: one BSON document can produce multiple Lucene documents for nested/embedded mappings.

### 2.3 Existing local files do not replace the authoritative catalog

`DefaultConfigManager` restores `configJournal.json`, and [`JournalWriter`](../src/main/java/com/xgen/mongot/config/backup/JournalWriter.java) persists physical generation/lifecycle intent. This is useful and should be retained initially.

However, distinguish three different things:

1. **Authoritative desired catalog:** what collections and index definitions the API user wants.
2. **Generation journal:** which physical generations are live, staged, or being dropped on this node.
3. **Mutation log/source store:** acknowledged document state and the events needed to catch indexes up.

The existing config journal is not a document WAL, source-document database, retry ledger, or complete substitute for a local desired-state catalog.

## 3. Target architecture

```text
                       Ordinary protobuf/gRPC clients
                                  |
                    TLS + authentication + authorization
                                  |
             +--------------------+--------------------+
             |                                         |
     StandaloneCatalogService                   DocumentWriteService
     create/get/list/update/drop                 insert/upsert/delete
             |                                         |
             +------------- ordered coordination ------+
                                  |
                      Embedded transactional database
                  +---------------+----------------+
                  | desired catalog and identities |
                  | full source BSON documents     |
                  | ordered logical mutation log   |
                  | request/outcome retry ledger   |
                  +---------------+----------------+
                                  |
              local reconciler + API ingestion/lifecycle manager
                                  |
                +-----------------+-------------------+
                |                                     |
          live generation                        staged generation
        log replay / tailing               source snapshot + log catch-up
                |                                     |
             IndexWriter -> existing mapping/analyzer/Lucene pipeline
                |
           commit checkpoints + reader refresh
                |
            existing readers, search commands, and cursor manager
```

No service in this standalone composition should construct a MongoDB connection, discover a replica set, fetch a collection UUID, poll MongoDB definitions, or publish a heartbeat to MongoDB.

### 3.1 Logical collection identity

Introduce a locally managed collection resource:

```text
collection: database/collection name -> stable UUID
index:      collection UUID + index name -> stable ObjectId
generation: index ObjectId + existing mongot generation identity
```

Generate the collection UUID locally and persist it before returning from `CreateCollection`. Reuse it everywhere the existing code expects `collectionUUID`. Database and collection names become local namespaces; they need not correspond to any MongoDB database.

Retain the existing internal `ObjectId`, UUID, and generation types initially. Removing all MongoDB-shaped types is unnecessary; removing the dependency on a MongoDB **server** is the goal.

Use collection IDs on write RPCs, not just collection names or physical generation IDs. Dropping and recreating a collection must produce a new UUID, so a delayed retry cannot write into an unrelated replacement collection. Never reuse an index ID for a newly created index after a drop.

### 3.2 Collection-centric ingestion

Write each document once to a collection. Its mutation log feeds all live indexes and any index builds for that collection.

This avoids asking a client to coordinate writes to separate text/vector indexes. It also supports creating additional indexes over existing documents, or recreating the first index after all prior indexes have been dropped without deleting their source documents.

For the normal synchronous write API, require at least one live, ready index and capture all relevant live generations as the request's target set. If there are no live indexes, or a required live target cannot accept indexing, reject before source acceptance with `FAILED_PRECONDITION`/`UNAVAILABLE`. New collections therefore create their first index before writing. A source-only import mode could be added separately, but must not claim that its writes are indexed.

There is no implied atomic visibility across indexes. Each generation has its own apply/commit/visibility watermarks. A query adapter can wait for particular indexes when read-your-writes is required.

## 4. Define the write contract before implementing the RPC

### 4.1 Mutation semantics

| Command | Required input | Recommended behavior |
| --- | --- | --- |
| INSERT | Complete BSON document with `_id` | Atomically create only if the source store has no document with that ID. Otherwise `ALREADY_EXISTS`. |
| UPSERT | Complete BSON document with `_id` | Replace the entire source document, or create it if absent. Fields omitted from the new document disappear. |
| DELETE | BSON ID wrapper, e.g. `{ "_id": <value> }` | Remove that ID from the source store and append a delete event. Missing IDs succeed as an idempotent no-op; still logging a delete is a simple, safe policy. |

Initially require client-assigned IDs. If server-generated IDs are added, retries must return the originally assigned ID rather than create another document.

Do not accept an API that claims strict INSERT while simply calling `DocumentEvent.createInsert()`. Likewise, do not silently interpret UPSERT payloads as partial patches.

### 4.2 BSON encoding and ID equality

Use `bytes` containing complete BSON documents inside protobuf messages. This preserves ObjectId, dates, binary/vector representations, decimals, and integer widths. JSON/`google.protobuf.Struct` alone can lose these distinctions.

For deletes, the payload is a BSON document containing exactly the `_id` field, not a JSON stringified ID and not an undocumented encoding of a bare BSON value.

Use the same ID encoding as [`LuceneDocumentIdEncoder`](../src/main/java/com/xgen/mongot/index/lucene/util/LuceneDocumentIdEncoder.java) for source-store keys and Lucene writes. That encoder serializes the BSON `{_id: value}` wrapper.

Specify equality explicitly. A practical v1 policy is **equality of the encoder's BSON bytes**, not the full MongoDB query engine's numeric/collation equivalence rules. Consequently an int32 ID and an int64 ID with the same numeric value can be different IDs. Initially limiting supported ID types to strings/ObjectIds is an option if broader BSON identity semantics are undesirable.

Validate before durable acceptance:

- Exactly one well-formed BSON document, with correct lengths and no trailing payload.
- Exactly one `_id`, non-null under the chosen API policy, of an allowed BSON type.
- No duplicate field names; reject rather than depend on driver/parser overwrite behavior.
- Document, nesting, batch-count, and aggregate request-byte limits.
- Encoded ID length within Lucene's maximum term length.
- Full replacement payload for INSERT/UPSERT; ID-only payload for DELETE.

The ID-length check is especially important: the current Lucene writer **logs and returns without writing** for an oversized encoded ID. A new public API must not translate that into successful ingestion.

Mapping-specific invalid fields need a separate policy. Existing ingestion can ignore or omit some unsupported/mismatched field values. Preserve and document that behavior, or add explicit strict validation; do not promise that successful acceptance means every field produced an indexed term/vector.

### 4.3 Acknowledgement, indexing, and visibility are different

Track the following per collection/generation:

| Position | Meaning |
| --- | --- |
| `acceptedSequence` | The source mutation and replay-log entry were durably committed locally. |
| `appliedSequence` | This generation has processed every accepted mutation through this position. |
| `committedSequence` | A successful index-engine commit persists this generation through this position. |
| `visibleSequence` | A newly acquired reader can observe this generation through this position. |

**Default write completion is synchronous indexing:** `Write` and `WriteBatch` keep the RPC pending until the mutation is durably accepted into the source store/log **and** every generation in the captured live target set has applied it successfully. Successful response means “indexed,” not merely “queued.” Staged mapping builds are not part of that target set; they catch up independently and cannot go live before their own barrier.

Capture and persist the target generation set under the same coordination used for admission/cutover/drop. A newly created index does not retroactively extend an already admitted request's wait. Fence generation retirement against pending writes, or explicitly fail affected waits if a requested drop makes their target unavailable; never return success before the target application has completed.

This does **not** require a Lucene commit or reader refresh per document. The durable source/log provides crash recovery while application-level indexing is synchronous. Batch application and periodic/grouped commits remain possible. If the caller needs immediate query visibility or an index-engine durability barrier, provide `AwaitSequence(index_id, sequence, condition)` for VISIBLE or COMMITTED. APPLIED is already guaranteed by a successful write response. A visible reader need not be durable, and a durable index commit need not refresh an already opened reader.

Sequences are collection-local, monotonically increasing positions, not MongoDB optimes/resume tokens. Include collection ID and generation identity with reported watermarks; a bare integer is insufficient across resources or rebuilds.

If a durably accepted source mutation fails during indexing, do **not** return indexing success. Return an explicit per-item indexing error with its accepted sequence when possible, or let an interrupted/deadlined caller recover the state through its request ID. The source mutation remains durable and can be retried/rebuilt; a failed RPC is not rollback. Report the blocked generation/sequence and never skip it silently or claim the index is caught up.

### 4.4 Retries and batch semantics

Use a required `request_id` on every mutating API. For ingestion, deduplicate by `(collection_id, request_id, mutation_ordinal)` and bind the request ID to a hash of the complete immutable request.

- Same request ID and identical content: return the original outcomes/sequences.
- Same request ID and different content: reject with `INVALID_ARGUMENT` or `FAILED_PRECONDITION`.
- A repeated strict INSERT with the same request ID returns its original success, not a duplicate-key error.
- DELETE followed by a delayed old UPSERT retry must not resurrect an old value. Deduplication must prevent reapplying the old accepted operation.
- Define a durable retry-retention interval. Once an entry expires, exactly-once retry recognition is no longer promised. Retain delete/version fences as needed for any longer-lived client sequencing contract.

For v1, make a batch **ordered but not atomic**. Process mutations in request order and return a per-item result. Earlier accepted mutations remain accepted if a later item fails. Specify whether processing continues after item errors; this guide recommends continuing.

Persist the request manifest, captured target generations, and each item's definitive **acceptance** outcome, including deterministic rejections. Track indexing completion separately from acceptance. If a process dies halfway through a batch, an identical retry reuses accepted sequences, waits for their replay/application as needed, and processes only items not yet accepted/rejected. Completed successes can return their original result without reapplication. Do not re-evaluate an already rejected INSERT against a now-different source state.

Both single and batch RPCs use this contract. A batch response waits until every item is either rejected or has reached indexing completion/an explicit indexing failure; do not return merely because the last item was appended to the log. Later valid items can be accepted after validation errors, but cannot be reported indexed past a blocked earlier sequence in the same required generation.

Structural/authentication failures can reject the entire RPC before any acceptance. A connection failure, deadline, or cancellation after acceptance leaves the caller uncertain; retry the same request ID or use `GetWriteResult`. Cancellation is not rollback. Request-level gRPC failure must not be interpreted as proof that no items committed.

## 5. Proposed gRPC surface

### 5.1 Use a new public service, not a renamed internal command stream

The existing server already uses gRPC, but its primary services are:

- `mongodb.CommandService`: MongoDB wire messages on bidirectional streams.
- `mongodb.BsonCommandService`: raw BSON search/vector command streams and a raw-BSON unary `manageSearchIndex` RPC.

These use hand-built method descriptors and custom marshallers. They are not ordinary generated protobuf document-write services. A client cannot use an arbitrary `.proto` `bytes` message to call a raw BSON method: protobuf field framing would change the on-wire payload.

Add a versioned protobuf API, for example `mongot.standalone.v1`, and generated Java messages/stubs. Reuse Netty/TLS infrastructure, but register new handlers rather than tunneling writes through query command names.

For isolation, a dedicated standalone server/listener is a clean first implementation. Sharing a listener is also possible, provided interceptors, admission limits, and authorization are correct for each service. [`MongoDbGrpcProtocolInterceptor`](../src/main/java/com/xgen/mongot/server/grpc/MongoDbGrpcProtocolInterceptor.java) currently applies its wire-version checks only to `mongodb.CommandService`; do not require those headers on the new service.

### 5.2 Core protocol sketch

This is a proposed core schema, not a file already built by this repository. Productionize naming, pagination, status details, compatibility rules, and descriptor generation before freezing v1.

```proto
syntax = "proto3";

package mongot.standalone.v1;
option java_multiple_files = true;
option java_package = "com.xgen.mongot.standalone.grpc";

service CatalogService {
  rpc CreateCollection(CreateCollectionRequest) returns (CollectionDescription);
  rpc GetCollection(GetCollectionRequest) returns (CollectionDescription);
  rpc CreateIndex(CreateIndexRequest) returns (IndexDescription);
  rpc UpdateIndex(UpdateIndexRequest) returns (IndexDescription);
  rpc DropIndex(DropIndexRequest) returns (IndexDescription);
  rpc GetIndex(GetIndexRequest) returns (IndexDescription);
  rpc ListIndexes(ListIndexesRequest) returns (ListIndexesResponse);
}

service DocumentService {
  rpc Write(WriteRequest) returns (WriteResponse);
  rpc WriteBatch(WriteBatchRequest) returns (WriteResponse);
  rpc GetWriteResult(GetWriteResultRequest) returns (WriteResponse);
  rpc AwaitSequence(AwaitSequenceRequest) returns (IndexWatermarks);
}

message CreateCollectionRequest {
  string request_id = 1;
  string database = 2;
  string name = 3;
}
message GetCollectionRequest { string collection_id = 1; }
message CollectionDescription {
  string collection_id = 1; // Canonical UUID string.
  string database = 2;
  string name = 3;
  uint64 accepted_sequence = 4;
}

enum IndexType {
  INDEX_TYPE_UNSPECIFIED = 0; // Reject, not an accidental default.
  INDEX_TYPE_SEARCH = 1;
  INDEX_TYPE_VECTOR_SEARCH = 2;
}
message CreateIndexRequest {
  string request_id = 1;
  string collection_id = 2;
  string name = 3;
  IndexType type = 4;
  bytes definition_bson = 5; // User mapping only, not internal IndexDefinition.
}
message UpdateIndexRequest {
  string request_id = 1;
  string index_id = 2; // Canonical ObjectId hex string.
  uint64 expected_revision = 3; // Required compare-and-set revision, starts at 1.
  bytes definition_bson = 4; // Complete replacement user definition.
}
message DropIndexRequest {
  string request_id = 1;
  string index_id = 2;
  uint64 expected_revision = 3;
}
message GetIndexRequest { string index_id = 1; }
message ListIndexesRequest {
  string collection_id = 1;
  uint32 page_size = 2;
  string page_token = 3;
}
message ListIndexesResponse {
  repeated IndexDescription indexes = 1;
  string next_page_token = 2;
}
message IndexDescription {
  string index_id = 1;
  string collection_id = 2;
  string name = 3;
  IndexType type = 4;
  uint64 desired_revision = 5;
  bytes desired_definition_bson = 6;
  string lifecycle_state = 7; // Define stable public states before release.
  IndexWatermarks live = 8;
  IndexWatermarks building = 9; // Absent if no staged build.
  string failure_message = 10;
  uint64 active_revision = 11; // Can differ from desired during a rebuild.
}

enum MutationType {
  MUTATION_TYPE_UNSPECIFIED = 0;
  MUTATION_TYPE_INSERT = 1;
  MUTATION_TYPE_UPSERT = 2;
  MUTATION_TYPE_DELETE = 3;
}
message Mutation {
  MutationType type = 1;
  bytes bson = 2; // Full document, or exactly {_id: value} for DELETE.
}
message WriteRequest {
  string collection_id = 1;
  string request_id = 2;
  Mutation mutation = 3; // Exactly one INSERT, UPSERT, or DELETE.
}
message WriteBatchRequest {
  string collection_id = 1;
  string request_id = 2;
  repeated Mutation mutations = 3;
}
enum WriteItemState {
  WRITE_ITEM_STATE_UNSPECIFIED = 0;
  WRITE_ITEM_STATE_REJECTED = 1;
  WRITE_ITEM_STATE_PENDING_INDEXING = 2;
  WRITE_ITEM_STATE_INDEXED = 3;
  WRITE_ITEM_STATE_INDEXING_FAILED = 4;
}
message MutationResult {
  uint32 ordinal = 1;
  int32 status_code = 2; // Canonical gRPC code, meaningful in terminal states.
  string message = 3;
  optional uint64 accepted_sequence = 4; // Present if durable, even on apply error.
  WriteItemState state = 5; // Only INDEXED is successful mutation completion.
}
message WriteResponse {
  string collection_id = 1;
  string request_id = 2;
  repeated MutationResult results = 3;
  bool complete = 4; // GetWriteResult can report an interrupted/in-progress batch.
}
message GetWriteResultRequest {
  string collection_id = 1;
  string request_id = 2;
}
enum SequenceCondition {
  SEQUENCE_CONDITION_UNSPECIFIED = 0;
  SEQUENCE_CONDITION_APPLIED = 1;
  SEQUENCE_CONDITION_COMMITTED = 2;
  SEQUENCE_CONDITION_VISIBLE = 3;
}
message AwaitSequenceRequest {
  string index_id = 1;
  string generation_id = 2; // Pin the generation; do not cross a swap silently.
  uint64 sequence = 3;
  SequenceCondition condition = 4;
}
message IndexWatermarks {
  string generation_id = 1;
  uint64 applied_sequence = 2;
  uint64 committed_sequence = 3;
  uint64 visible_sequence = 4;
}
```

Additional contract rules:

- Management success means the desired-state mutation is durable, not that an index is built. `GetIndex` distinguishes desired/active revision, build progress, and failure.
- Document success is stronger: `Write` (single item) and `WriteBatch` wait for durable source acceptance and application to all captured live targets. `GetWriteResult` may report pending work; it is not an alternative acceptance-only completion contract.
- Only an INDEXED item is successful, with status code OK. PENDING_INDEXING is nonterminal and is exposed by `GetWriteResult`, not as successful completion of a normal write. A batch can have indexed, rejected, and indexing-failed terminal items; clients must inspect each item rather than only the RPC's transport status.
- Reject an absent single mutation and empty/oversized batches. Bind request digests to the RPC operation as well as payload; do not accidentally confuse a single write with a differently shaped batch.
- Repeated `CreateIndex` with an equivalent complete definition can return the existing ID. Conflicting definitions return `ALREADY_EXISTS`.
- Management request IDs need the same durable retry treatment as document writes, scoped by operation/resource or globally by principal as documented.
- A revision mismatch returns `ABORTED` or `FAILED_PRECONDITION`, never a last-writer-wins overwrite.
- `DropIndex` first records durable deletion intent, fences new work, then drains/deletes physical resources asynchronously. Keep a tombstone long enough for retries.
- Resolve `AwaitSequence` against a generation belonging to the specified index and collection. Fail if it is retired/dropped or permanently failed before the target position.
- Make `ListIndexes` desired-catalog-driven and include failed/uninitialized indexes, not only initialized writers. Page tokens should bind to a catalog revision or declare best-effort pagination.
- Add standard gRPC health. Reflection is useful for development; expose it in production only deliberately.

Unary single-document and batch writes are sufficient for the initial design. Both can wait on asynchronous server-side completion futures without occupying Netty event-loop threads. There is no need to add streaming writes or a public asynchronous enqueue API just to handle backpressure.

## 6. Local persistence and durability

### 6.1 Recommended embedded-store responsibilities

Choose an embedded engine that can atomically update source state, append the logical mutation, and persist its retry outcome. An initial implementation could use SQLite with WAL mode and `synchronous=FULL`; RocksDB with carefully designed atomic batches is another option. Neither is an existing standalone subsystem in this repository, and a new dependency requires build, native-library, licensing, and durability review.

SQLite's physical WAL is not the logical replay API. Maintain a logical `mutations` table with stable collection positions. Do not replay database implementation WAL files into Lucene.

Suggested logical records:

| Record | Essential fields |
| --- | --- |
| `collections` | UUID, database/name, lifecycle/epoch, last accepted sequence |
| `indexes` | index ID, collection UUID, name/type, raw user definition, normalized internal definition, desired revision, deletion intent |
| `requests` | scope/principal, request ID, immutable request digest, captured target generations, progress, retention deadline |
| `request_items` | request ID + ordinal, immutable acceptance outcome/sequence, separately tracked indexing completion |
| `documents` | collection UUID + encoded ID, full BSON, last accepted sequence |
| `mutations` | collection UUID + sequence, encoded ID, replacement BSON or delete, request identity |
| `builds` | index/generation, definition revision/hash, snapshot/catch-up state, log-retention pin |
| `schema` | storage schema version, instance identity, migration state |

The `documents` primary key is also the uniqueness check for strict INSERT. Source-store and Lucene ID equality must match.

### 6.2 Atomic mutation acceptance

Under the collection's ordered acceptance lane:

1. Authenticate/authorize; validate the request envelope, limits, and BSON structure; acquire bounded in-flight capacity and capture a valid live indexing target set.
2. Create or verify its durable request manifest/digest.
3. For each item, look for an existing definitive outcome.
4. In one embedded-database transaction:
   - Check collection existence/epoch and INSERT uniqueness.
   - Allocate the next sequence for accepted operations.
   - Replace/remove the source document.
   - Append the replay event, including a complete replacement document for upserts.
   - Record the original outcome and advance request progress.
5. Commit with the configured durable storage guarantee.
6. Notify index consumers, then keep the RPC pending until the captured targets apply the accepted positions or an explicit error/deadline occurs. Return indexing-complete results, not acceptance-only success.

Deterministic rejection outcomes should also be recorded transactionally, but do not consume a mutation sequence. Bound request-manifest retention and batch size so retry metadata cannot grow without limit.

Do not acknowledge after merely submitting a task to an executor, copying bytes into memory, or committing the source transaction without applying it to the targets. Keep source transaction locks released while waiting for indexing. Storage durability is limited by the underlying filesystem/device honoring sync operations; this is still a single-node guarantee, not replicated availability.

### 6.3 Projection checkpoints

Introduce a standalone commit envelope encoded through `EncodedUserData`, for example:

```json
{
  "schemaVersion": 1,
  "sourceMode": "api",
  "instanceId": "local-instance-uuid",
  "collectionUUID": "local-collection-uuid",
  "generationId": "existing-mongot-generation-identity",
  "definitionHash": "hash-of-normalized-internal-definition",
  "lastAppliedSequence": 12345
}
```

Call it something like `StandaloneIndexCommitUserData`. Do not fabricate MongoDB resume tokens/optimes or reuse `IndexCommitUserData` in ways that make MongoDB recovery code think this is a partially replicated MongoDB index.

[`EncodedUserData`](../src/main/java/com/xgen/mongot/index/EncodedUserData.java) is an opaque string wrapper, so a source-specific checkpoint envelope can be implemented without changing its fundamental role. Give it strict version/identity checks and explicit upgrade rules.

At a commit barrier `S`:

1. Ensure the generation has processed every mutation through `S` and no unfinished earlier work remains.
2. Serialize checkpoint publication and commit against application to that generation. The simplest first implementation pauses that generation's apply lane while committing.
3. Commit the writer with checkpoint `S`.
4. Advance in-memory `committedSequence` only after commit succeeds.

Never advertise an offset beyond the completed contiguous prefix. `getCommitUserData()` on a live writer can reflect staged/live metadata; it must not be treated as proof that an attempted commit succeeded. On restart, reopen the engine and read its actual persisted commit.

`IndexWriter.commit()` is not a universal durability barrier: its interface explicitly permits implementations whose engine schedules its own commits to make it a no-op. **Constrain v1 to the audited Lucene writers**, or add an explicit durable-checkpoint capability for other engines before promising COMMITTED semantics. Also account for `CommitDataStagingWriter` if supporting those engines later.

### 6.4 Partitioned indexes

`MultiLuceneIndexWriter` hashes the encoded ID to a partition and commits partition writers in reverse order, leaving partition zero last. `getCommitUserData()` reads partition zero; the existing design relies on replay when a crash interrupts the multi-partition commit.

Preserve this ordering and its replay invariants:

- Use the conservative persisted checkpoint when reopening.
- Replay the ordered suffix, even if another partition already contains some later writes.
- Keep queries off a recovering generation until ordered replay/catch-up and refresh complete; replay can temporarily replace a newer already-persisted value with an earlier event before reaching the final value again.
- Do not describe a multi-partition commit as an atomic cross-partition transaction.
- Apply/commit/replay tests must include several operations on the same ID across a crash boundary.

### 6.5 Recovery algorithm

On restart:

1. Open and validate the local authoritative store; recover its own transactional state.
2. Load desired definitions and physical-generation journal; reconcile pending create/update/drop intents.
3. Open each relevant index generation and validate its persisted checkpoint identity/format.
4. Reconstruct generation failure/build status from standalone state, not from MongoDB resume information.
5. Replay all retained collection mutations after the generation's conservative checkpoint, in order.
6. If the required suffix is unavailable, rebuild from the source store; never silently skip ahead.
7. Commit and refresh at a known barrier, then expose the live generation as queryable.

If a generation has no valid standalone checkpoint, either initialize it as genuinely new and build it from a source snapshot, or fail/rebuild it explicitly. An unexpected pre-existing nonempty index is not an empty collection.

### 6.6 Log retention and backups

Garbage-collect logical mutations only when every relevant active generation has durably passed them and no snapshot/build/backup consumer pins them. Failed consumers need an explicit policy: retain their log, or mark them for a full source rebuild and release the pin. Never let a failed index consume disk indefinitely without admission limits/alerts.

The full source store allows old log segments to be removed; it does not make arbitrary trimming safe for a running snapshot build. Keep a persistent retention floor/pin and test crash recovery of its ownership.

Backups must include the authoritative catalog, full source state, retry ledger, necessary mutation suffix, and a consistent generation/journal manifest. A stopped-process backup is the simplest v1 procedure. For online backups, use the embedded engine's snapshot/backup facility and pinned Lucene commits, or intentionally restore by rebuilding all indexes from a consistent source backup. Copying only Lucene directories is not enough to preserve standalone service state.

## 7. Replace replication with API ingestion lifecycle

### 7.1 Why the existing no-op replication manager is insufficient

[`MongoDbNoOpReplicationManager`](../src/main/java/com/xgen/mongot/replication/mongodb/MongoDbNoOpReplicationManager.java) is a paused/unavailable-MongoDB mode, not an API-ingestion mode. It reports replication unsupported and reconstructs status from MongoDB `IndexCommitUserData`. A new index without resume information becomes `NOT_STARTED`.

That matters because:

- Query eligibility depends on `IndexStatus`.
- [`PeriodicLuceneIndexRefresher`](../src/main/java/com/xgen/mongot/index/lucene/PeriodicLuceneIndexRefresher.java) refreshes readers only for `STEADY` indexes.
- Existing staged-index swapping uses query-serviceable status to decide whether a replacement can go live.

Simply disabling replication can therefore leave an initialized writer that neither becomes queryable nor refreshes normally.

### 7.2 Two integration options

**Pragmatic first implementation:** implement an `ApiIngestionManager` behind the existing [`ReplicationManager`](../src/main/java/com/xgen/mongot/replication/ReplicationManager.java) lifecycle interface. Supply its factory directly to the standalone lifecycle composition, even though `SyncSourceConfig` is empty. Do not go through a factory that selects `MongoDbNoOpReplicationManager` when no MongoDB URI exists.

Responsibilities:

- `add(IndexGeneration)`: obtain the initialized writer, restore its standalone checkpoint, register ordered replay/build work, and publish correct readiness after catch-up.
- `dropIndex(GenerationId)`: fence new apply work, cancel/drain queued/in-flight tasks and commits/refreshes, kill the generation's cursors, and complete only when no ingestion task can use the writer.
- `shutdown()`: stop admissions/consumers, drain or safely abandon unacknowledged work, persist required barriers, stop commit/replay executors, and wait for their completion.
- `getSyncSourceConfig()`: empty.
- `isInitialized()`: local startup/replay-registration readiness, with precisely documented meaning.
- `isReplicationSupported()`: if required by legacy plumbing, treat this as “active ingestion supported” in this adapter; document the naming mismatch. Do not let MongoDB disk/restart logic act on it.

Let the existing index actions own physical close/drop after the lifecycle future completes. Avoid closing the same resources independently from both the ingestion adapter and `IndexActions`.

**Cleaner long-term implementation:** extract an ingestion-neutral `IndexIngestionManager` interface and readiness capability, with MongoDB and API implementations. Rename lifecycle concepts such as `startReplication` to `startIngestion` where appropriate. This removes the legacy semantic adapter but has a larger initial change surface.

Whichever option is chosen, keep the full existing physical initialization path: `IndexFactory.getInitializedIndex()`, `InitializedIndexCatalog`, generation identity, cursor cleanup, staged/phasing-out handling, and index metrics. Do not instantiate an unrelated bare Lucene writer alongside mongot's indexes.

### 7.3 Writer bridge

The document-event bridge can remain small:

```java
// Sketch: invoked from a generation-owned ordered consumer after durable acceptance.
RawBsonDocument document = new RawBsonDocument(replacementBson);
DocumentMetadata metadata =
    DocumentMetadata.fromOriginalDocument(Optional.of(document));
DocumentEvent event = DocumentEvent.createUpdate(metadata, document);
writer.updateIndex(event); // Full replacement / upsert.

// For DELETE:
writer.updateIndex(DocumentEvent.createDelete(documentId));
```

For a batch, retain the `writer.prepareBatch(events)` hook before applying its returned events. It matters to implementations maintaining auxiliary ID mappings, even if the initial supported Lucene-only path does not need additional preprocessing.

Keep analysis safeguards and index-limit handling. [`DefaultDocumentIndexer`](../src/main/java/com/xgen/mongot/replication/mongodb/common/DefaultDocumentIndexer.java) is a useful reference for `RegexBudget`, persistent analysis failure, `prepareBatch`, commit staging, and exceeded-limit handling. It is not a drop-in standalone indexer: it reads MongoDB checkpoint metadata and some I/O paths terminate the process via `Crash`.

Prefer extracting shared indexing safety logic or writing a standalone adapter with the same protections. Decide which failures are item-level validation errors, generation failures, retryable storage failures, and process-fatal corruption. Do not let an ordinary invalid RPC become a process crash.

### 7.4 Ordering and resource ownership

A safe initial concurrency model is:

- One ordered acceptance lane per collection for source mutations and collection/index coordination barriers.
- One ordered application lane per generation, consuming collection positions in sequence.
- A serialized desired-catalog mutation/reconciliation lane for global catalog invariants.
- Explicit barriers for commits, reader refreshes, generation cutovers, and drops.

Parallelize across collections/generations first. Lucene writer thread safety does not by itself establish the intended order of two replacements/deletes for the same ID.

If later parallelizing inside a generation, key-affine scheduling must preserve per-ID order and checkpoint publication must still wait for the completed contiguous sequence prefix. A fast later task must not move the checkpoint past an unfinished earlier task.

`IndexCatalog` lookup is not a writer lease: its documentation explicitly says a returned index can subsequently be removed. The ingestion manager needs lifecycle fencing/ownership that prevents writer closure during RPC/worker use. Avoid holding a global config lock while waiting on long disk I/O or task drains; define lock ordering and use bounded waits to prevent shutdown/drop deadlocks.

## 8. Local index management and reconciliation

### 8.1 Parse public mappings, generate internal identity locally

Accept the same user-facing mapping shape already modeled by `UserSearchIndexDefinition` and `UserVectorIndexDefinition`.

Example text definition:

```json
{
  "mappings": {
    "dynamic": false,
    "fields": {
      "title": { "type": "string", "analyzer": "lucene.standard" },
      "category": { "type": "token" }
    }
  },
  "storedSource": true
}
```

Example client-vector definition:

```json
{
  "fields": [
    { "type": "vector", "path": "embedding", "numDimensions": 3,
      "similarity": "cosine" },
    { "type": "filter", "path": "category" }
  ],
  "storedSource": true
}
```

The RPC carries these as BSON. It should not require a user to invent `indexID`, `collectionUUID`, feature versions, or physical generations.

Conversion flow:

1. Resolve the local collection UUID/database/name.
2. Parse the BSON with `UserIndexDefinition.fromBson(parser, type)` and the existing strict field parsers.
3. Generate or retain the index ObjectId and immutable index type.
4. Convert with `IndexMapper.toInternal()`, passing the locally resolved identity and an internally managed definition version/timestamp.
5. Run complete definition/catalog validation before persisting desired state.

The public CAS revision can be separate from the existing internal `definitionVersion`; document the translation. Do not expose internal version/engine compatibility fields as unrestricted client overrides.

### 8.2 Reuse validation, not MongoDB-specific side effects

Extract reusable validation from the AIC management commands rather than invoking those commands unchanged. Preserve:

- Name/identity uniqueness and cross-type invariants.
- Analyzer definitions and field analyzer references.
- Embedded-document nesting limits and sort validation.
- Vector field/path/dimension/engine constraints and nested-root validation.
- Feature/version compatibility and supported-partition limits.

Also enforce the standalone feature allowlist before catalog acceptance: no MongoDB-backed views, external synonym sources, materialized-view auto-embedding, or unsupported custom vector engines.

Review idempotence comparisons carefully. `IndexMapper.areEquivalent()` currently compares selected fields, not every user-visible definition property. For example, its search comparison does not include sort; the vector comparison does not include every stored-source/nested-root property. Do not blindly use it as the complete standalone API's definition equality/hash contract. Implement tested canonical normalization that includes every supported semantic field and relevant defaults.

### 8.3 Persist desired state before starting work

For Create/Update/Drop:

1. Serialize the mutation and validate it against the latest complete desired catalog.
2. Apply the CAS revision check and durable request deduplication.
3. Atomically persist the desired-state mutation and original API result.
4. Notify the local reconciler.
5. Return durable metadata acceptance; report asynchronous build/drop progress separately.

The local reconciler replaces `CommunityConfigUpdater` in this mode. It reads the local authoritative catalog and calls:

```java
configManager.update(
    allDesiredVectorDefinitions,
    allDesiredSearchDefinitions,
    supportedAnalyzerOverrides,
    Set.of(), // No MongoDB collection-resolution set.
    unreadableButStillDesiredIndexIds);
```

**Pass a complete desired snapshot, not only the index touched by this RPC.** `ConfigManager.update()` reconciles desired state; giving it a singleton can make other indexes look deleted.

Use notification-driven reconciliation plus a periodic local backstop for asynchronous swaps/retirement. Ensure older snapshots cannot run after newer ones and revert desired state. Refresh local cached index information for status RPCs as lifecycle state changes.

Never turn a failed catalog read into an empty desired list. Preserve unreadable-but-still-desired IDs using the existing quarantine mechanism, or fail closed. An upgrade must not erase indexes just because this binary cannot parse a definition.

Keep `configJournal.json` as physical generation intent during the first implementation. The embedded catalog is authoritative for **desired** definitions; the journal is authoritative for its existing **physical lifecycle** transitions. No cross-store transaction is assumed: durable desired intent precedes reconciliation, and restart repeats unfinished reconciliation idempotently. Version/validate both stores and test crashes between their writes.

### 8.4 Prevent MongoDB recovery behavior from leaking into standalone mode

`DefaultConfigManager.updateCycle()` does more than create/drop indexes: it stages recovery attempts and swaps query-serviceable staged indexes. Audit these paths and make recovery/build eligibility ingestion-mode-aware.

In particular:

- Do not schedule a MongoDB initial sync when a standalone index fails.
- Do not discard a local index because a UUID cannot be resolved on mongod.
- Do not let generic failure-recovery staging bypass the local snapshot/replay coordinator.
- Do not mark an empty staged writer `STEADY` merely because initialization succeeded.
- Ensure every path that can swap a staged generation respects build completeness, including fallback swaps when the old generation has failed.

An empty brand-new collection can become ready quickly. A new index on a populated collection cannot.

## 9. Creating and updating mappings without MongoDB

### 9.1 Why keeping original documents matters

Analyzed terms and indexed vector/filter fields do not reconstruct the complete original BSON document. `storedSource` may be disabled or contain only selected fields. Therefore a standalone service cannot generally rebuild a new mapping from its existing Lucene postings.

There are only three sound sources for a rebuild:

1. A retained full local source store — recommended.
2. A retained complete history from collection creation, replayed from the beginning.
3. An explicit client-provided full reload into a replacement generation/index.

If none exists, reject mapping changes/new indexes requiring historical data with `FAILED_PRECONDITION`; never report success for a replacement containing only future writes.

### 9.2 Initial build and replacement-build algorithm

Use the same algorithm for a first index on an existing collection and an updated mapping:

1. Persist desired revision and allocate a staged generation using existing generation machinery.
2. Under collection coordination, acquire a source-store snapshot at sequence `S` and establish a durable log-retention pin before later writes can make the required suffix eligible for pruning. The snapshot's documents and `S` must come from the same transactional view.
3. Keep the staged generation non-queryable (`INITIAL_SYNC` can serve as an internal BUILDING state).
4. Scan the snapshot's live documents into the staged writer with the **new** mapping.
5. After the scan, replay accepted mutations `S+1...` in order, including deletes. Do not apply a later upsert before an older snapshot copy of the same ID.
6. At a collection coordination barrier, select cutover position `T` and pause or buffer later application briefly.
7. Ensure staged application has reached `T`, commit it with checkpoint `T`, and explicitly refresh its reader(s).
8. Persist the ready/build-complete condition for this exact generation, revision, and checkpoint. Only now make it eligible for the existing generation swap.
9. Perform the durable journaled swap; route later application to the new live generation. A restart must deterministically finish or recover this cutover, not promote an empty writer.
10. Retire the old generation through existing cursor-aware phasing-out cleanup. Release build pins only when they are no longer needed.

The collection log lets the old live generation continue serving and consuming writes while a replacement builds. Direct dual-write is not mandatory: each generation can independently consume the same ordered log.

[`StagedIndexesSwapper`](../src/main/java/com/xgen/mongot/config/manager/StagedIndexesSwapper.java) already persists swap intent before changing the live catalog, and old generations enter phasing-out state. Reuse that foundation, but add standalone build-completeness guards to all relevant swap paths. Do not use queryable status as an unverified substitute for a replay barrier.

Because the existing periodic refresher only runs at `STEADY`, provide an explicit final refresh for a staged build before publishing it. Switching status and hoping the next refresh happens before a query is not a visibility guarantee.

### 9.3 Interrupted builds

Persist build metadata and checkpoint progress, but do not assume an in-memory embedded-database read snapshot survives a restart.

For the first version, an interrupted snapshot scan can simply discard/reinitialize its staged generation and start from a fresh consistent snapshot. Retain the old live generation. Remove obsolete log pins only after durable cancellation/replacement of the abandoned build.

Later, resumable snapshots need a persistent snapshot/export or equivalent engine facility. A saved “last scanned ID” alone is not enough if the underlying source snapshot changed.

Long-running SQLite read snapshots can retain a large physical database WAL. Monitor and bound this cost; consider a source snapshot/export or an engine suited to the eventual write/build workload.

### 9.4 Prototype alternative: explicit reload

If omitting a source store, make the limitation visible in the API:

```text
Create replacement index/mapping
  -> client bulk-reloads full current documents
  -> client coordinates ordered concurrent writes/deletes or pauses writes
  -> explicit complete-build barrier
  -> commit + refresh + generation switch
```

A plain `UpdateIndex` that silently creates an empty index is not an acceptable shortcut.

## 10. Standalone startup, health, and shutdown

### 10.1 Add an explicit composition root

A separate `MongotStandalone` executable and `StandaloneMongotBootstrapper` avoid making every MongoDB-backed dependency optional throughout `OnPremMongotBootstrapper`. Shared initialization helpers can be extracted without copying its MongoDB composition.

Alternatively, add a mode discriminator with separately validated configuration variants. Do not merely change `CommunityConfig.syncSource` to optional and leave existing call sites assuming it exists.

Reuse the existing process requirements: Java 21, security-provider installation, vector native initialization where needed, feature-flag initialization, analyzer registry setup, logging/tracing, metrics, disk monitoring, and the Lucene factory.

### 10.2 Remove or replace these bootstrap dependencies

| Existing dependency | Standalone treatment |
| --- | --- |
| `InitialSyncHostProvider`, connection/replica-set discovery | Do not instantiate. |
| `SyncSourceConfig` and MongoDB replication factories | Replace with explicit API-ingestion factory/configuration. |
| `MongoDbMetadataClient.refreshServerInfo()` | Replace MongoDB-derived capability/version metadata with explicit local capabilities; audit command factories that consume it. |
| `MongodTopologyMonitor`, `CatalogAccessGuard` | Do not construct or invoke on standalone management/readiness paths. |
| `DefaultMetadataService`, MongoDB authoritative catalog | Local authoritative store. |
| `CommunityConfigUpdater`, dropped-MongoDB-collection cleanup | Local reconciliation and explicit local collection lifecycle. |
| `CommunityMetadataUpdater`, MongoDB server heartbeat/stats | Local status API and normal metrics. |
| `CommunityReadinessChecker` | Local startup/recovery/store/listener readiness. |
| Materialized-view/auto-embedding MongoDB client, leases, factories | Disable for v1; supply explicitly absent/no-op capabilities without constructing MongoDB clients. |
| MongoDB synonym replication | Reject such definitions until a local/API synonym source is implemented. |

The existing [`HealthManager`](../src/main/java/com/xgen/mongot/server/grpc/HealthManager.java) depends on `configManager.isReplicationInitialized()`. [`CommunityReadinessChecker`](../src/main/java/com/xgen/mongot/config/provider/community/CommunityReadinessChecker.java) additionally reads MongoDB topology, authoritative definitions, stats, and server state. Neither should be reused unchanged as the standalone readiness definition.

Expose separate concepts:

- **Liveness:** the process is running and not fatally broken.
- **Management readiness:** local store/catalog recovered and management requests can be processed; an empty service must be ready to create its first collection/index.
- **Write readiness:** source-store commits and bounded ingestion admission are available.
- **Index query readiness:** that specific generation has completed required recovery/build/refresh.

An index build must not prevent the management API from coming up. A staged replacement should not block normal writes through the old live generation. Normal document writes require their captured live targets to be ready; creating the first collection/index does not. Avoid a readiness deadlock where creating the first index requires an already-ready index.

### 10.3 Startup order

1. Parse/validate standalone config; acquire an exclusive instance/data-directory lock.
2. Initialize process-level logging, security, native support, feature flags, and metrics.
3. Open/migrate the authoritative embedded store; recover requests and desired catalog.
4. Construct catalogs, cursor manager, Lucene factory, and API-ingestion lifecycle implementation.
5. Restore physical journal and reconcile desired state, honoring deletion/build intent before allowing a generation to serve queries.
6. Recover/register generation consumers; replay or start source builds as required.
7. Register shutdown hooks and start management/document/health listeners with appropriate readiness gates.
8. Publish per-index query readiness as generations catch up; run local reconcile/commit/metrics backstops.

Do not permit multiple processes to open the same standalone source store/data path as independent writers. Shared-directory use is not a substitute for replication or leader election.

### 10.4 Example proposed configuration

**Illustrative only — these options do not exist yet:**

```yaml
mode: standalone
storage:
  dataPath: /var/lib/mongot-standalone
  sourceStore:
    engine: sqlite
    synchronous: full
server:
  grpc:
    address: 127.0.0.1:27029
    # Bind beyond loopback only with configured TLS/authentication.
ingestion:
  writeCompletion: indexed
  maxInFlightRequests: 64
  maxDocumentBytes: 16777216
  maxBatchDocuments: 1000
  maxBatchBytes: 33554432
  maxQueuedBytes: 268435456
  maxUnindexedLogBytes: 10737418240
  commitIntervalMillis: 1000
  retryRetentionHours: 24
querying:
  refreshIntervalMillis: 1000
healthCheck:
  address: 127.0.0.1:8080
```

These are example starting limits, not measured recommendations. Enforce the aggregate gRPC transport limit above the application payload limit by enough for protobuf overhead, and reject oversize compressed/decompressed inputs safely. Existing MongoDB replication durability options should not silently define the new API's acknowledgement policy.

### 10.5 Shutdown/drop sequencing

Graceful shutdown should:

1. Mark write readiness false; stop admitting new writes and new builds.
2. Allow in-flight acceptance transactions to finish or roll back; preserve committed outcomes despite disconnects.
3. Stop generation consumer admission and drain/fence outstanding apply work.
4. Finish chosen commit/visibility barriers, or leave a conservative checkpoint and retained replay log for restart.
5. Stop/await replay, commit, refresh, and build tasks before their writers close.
6. Close cursors/readers/writers/factories in the established lifecycle order.
7. Close the authoritative store, metrics/tracing, and logging last as appropriate.

A DROP of one index follows the same fencing/draining rules for its generations but **does not delete collection documents** or data needed by other indexes. Add a separate, explicitly destructive collection-delete API only when its semantics are designed, including its interaction with queued retries and backup retention.

## 11. Querying without mongod

The requested change is primarily ingestion and management, but a truly standalone process also needs a deliberate query boundary.

Mongot is not the MongoDB aggregation executor. In normal deployments, mongod/mongos perform additional work, including source-document lookup in query paths that return IDs/search metadata. Removing replication does not automatically turn mongot into a complete replacement for MongoDB's aggregation API.

Two reasonable initial choices:

1. **Keep internal search/vector gRPC commands for a trusted adapter/test client.** Populate the expected database/name/collection UUID from the local catalog. Audit command capability/topology metadata and expose only supported commands. Do not route API document writes through those command streams.
2. **Add a public query facade later**, backed by the existing parsers/readers/cursor manager. It should resolve local identity, define result documents versus IDs/scores, support bounded pagination/cancellation, and optionally honor a minimum visible sequence.

For full result documents:

- Existing `storedSource: true` plus a supported return-stored-source query path can return documents consistent with the indexed document version. Verify text and vector behavior and feature flags separately.
- Alternatively hydrate IDs from the local source store, but disclose consistency: it can contain a newer document than the version that matched the index, or no document after a more recent delete.
- `storedSource` defaults to disabled in [`StoredSourceDefinition`](../src/main/java/com/xgen/mongot/index/definition/StoredSourceDefinition.java). Do not assume full documents are already retained inside every index.
- A full source store for rebuilding and a configured stored source for search results are distinct features.

Keep cursor pinning, timeout, kill-on-drop, and old-generation retention behavior. A visibility barrier applies to newly acquired readers; an already-open cursor can legitimately retain its earlier snapshot.

## 12. Features that otherwise reintroduce MongoDB

| Feature | First standalone release | Possible later replacement |
| --- | --- | --- |
| Plain text mappings/custom analyzers | Reuse existing implementation | Extend supported analyzer features normally. |
| Client-supplied vectors/filter fields | Support audited Lucene-native path | Add other engines after checkpoint/durability integration. |
| Views/aggregation-based ingestion transformations | Reject | Explicit local transform pipeline, with documented `_id` semantics. |
| Synonyms stored in a MongoDB collection | Reject nonempty MongoDB source definitions | Versioned synonym documents uploaded through API/local collection, local builder refresh and persistence. |
| Materialized-view auto-embedding | Reject | Local embedding jobs/cache, durable outputs and retry/version handling, no MongoDB leases/materialized collections. |
| Custom vector engines/auxiliary mappings | Reject until audited | Implement engine-specific prepare/apply/checkpoint/refresh/rebuild barriers. |
| Shard routing, UUID resolution, topology-dependent planning | Disable | Standalone-specific query/routing layer if clustering is later added. |
| MongoDB server state/index stats | Replace | Local RPC status, metrics, and health. |
| MongoDB-derived collection deletion/rename detection | Disable | Explicit local collection metadata operations. |

Even optional construction of a materialized-view MongoDB client or metadata poller can violate the “no mongod” requirement. Test the composition, not just the happy path without exercising those clients.

## 13. Security, overload control, and observability

### 13.1 Security

The method name `AuthenticatedCommandStream` is not an authentication guarantee. `CommandStreamMethods` notes that the existing gRPC server does not itself implement authentication there, and the community bootstrap currently constructs `SecurityConfig.createAuthDisabled(...)` while configuring transport TLS.

For the public standalone API:

- Default to loopback for unconfigured development mode.
- Require TLS and authenticate external clients (for example mTLS with explicit principal mapping, or validated bearer tokens).
- Authorize collection writes separately from catalog creation/update/drop and maintenance operations.
- Scope request deduplication so one principal cannot retrieve another principal's results or reserve its request IDs.
- Reuse secure TLS setup where possible, but add explicit principal extraction and authorization interceptors.
- Avoid logging source documents, secrets, complete request payloads, or raw IDs by default.
- Limit message/decompression sizes, batch counts, deadlines, concurrent streams, request-ledger retention, and management/build concurrency.

### 13.2 Backpressure

**Use synchronous request completion as the primary backpressure mechanism.** Expose `Write` for one document operation and `WriteBatch` for bounded batches. Keep the client waiting until indexing has completed on the captured live target generations. A slow writer therefore directly slows that client's request rate, without requiring a public async queue or a separate polling protocol.

Server implementation:

1. Reserve a bounded number of in-flight requests and bytes, accounting for full batch size. Reserve before durable acceptance, not after unbounded tasks have accumulated.
2. Validate, durably record the source mutations, and schedule their ordered application.
3. Keep the RPC open while a completion future/barrier waits for the required generation positions.
4. Complete the response only when indexing succeeds or an explicit rejection/indexing failure/deadline is known.
5. Release request capacity when its waiter finishes; separately retain/charge any accepted replay backlog until consumers catch up. A disconnected caller must not make its still-pending durable mutations disappear from backlog accounting.

“Block the client” means the request remains pending; it does not mean blocking a gRPC/Netty event-loop thread. Use dedicated bounded worker executors and asynchronous response completion. A simple per-generation serial consumer is adequate initially. Apply a batch efficiently, then wait at its barrier rather than issuing a commit/refresh or a separate blocking task for every item.

Blocking alone does not bound load from many concurrent clients. When admission capacity is full, either wait in a **bounded**, deadline-aware admission queue, or return `RESOURCE_EXHAUSTED` before acceptance. Prefer short/no server admission queues over hiding an unbounded backlog. Configure client deadlines/retry backoff and a maximum number of simultaneous requests.

Maintain a durable-backlog disk budget for crash recovery, failed generations, and staged builds even though successful foreground writes are synchronously indexed. Once accepted, retain and recover a mutation despite cancellation or indexing failure. Preserve free-disk headroom for commits, merges, snapshots, and recovery; dropping log data to free space is not acceptable.

Translate disk-monitor gates into **ingestion admission and worker policy**, not a request to restart MongoDB replication. Consider source-store and Lucene space together. An index failure or long build should not produce an unbounded accepted log.

### 13.3 Minimum metrics/status

- Accepted/rejected/retried INSERT/UPSERT/DELETE counts and bytes.
- End-to-end synchronous single/batch latency, durable acceptance latency, per-generation apply latency, commit latency, refresh latency.
- In-flight request/byte permits, waiting admission requests, indexing wait duration, deadline/cancellation counts.
- Queue bytes/counts, logical log bytes, retention pins, retry-ledger size.
- Accepted/applied/committed/visible positions and lag for each generation.
- Build revision, snapshot progress, catch-up lag, errors, and cutover duration.
- Source-store/SQLite WAL size if applicable, disk headroom, Lucene merge backlog.
- Per-index analysis/field/vector failures and the first blocked sequence.
- Startup recovery duration, replay volume, readiness, authentication/authorization failures.

Replace MongoDB optime/replication-lag reporting with local sequences; do not synthesize timestamps that look like valid MongoDB replication progress. Bound metric-label cardinality, especially for request IDs and document IDs.

## 14. Suggested change map

The following new names/paths are suggestions. Keep new modules separate enough that the MongoDB-backed community composition can retain its existing dependencies and behavior.

```text
src/proto/standalone/v1/standalone.proto
src/proto/standalone/v1/BUILD

src/main/java/com/xgen/mongot/standalone/
  MongotStandalone.java
  StandaloneConfig.java
  StandaloneMongotBootstrapper.java
  StandaloneReadiness.java

src/main/java/com/xgen/mongot/standalone/catalog/
  LocalAuthoritativeCatalog.java
  LocalCatalogReconciler.java
  StandaloneDefinitionValidator.java

src/main/java/com/xgen/mongot/standalone/store/
  StandaloneStore.java
  SourceDocumentStore.java
  MutationLog.java
  RequestLedger.java

src/main/java/com/xgen/mongot/standalone/ingestion/
  ApiIngestionManager.java
  StandaloneDocumentIndexer.java
  StandaloneIndexCommitUserData.java
  GenerationConsumer.java
  IndexBuildCoordinator.java

src/main/java/com/xgen/mongot/standalone/server/
  StandaloneGrpcServer.java
  CatalogGrpcService.java
  DocumentGrpcService.java
  StandaloneAuthInterceptor.java
```

Existing areas likely requiring extraction or targeted modification:

| Existing area | Expected change |
| --- | --- |
| `config/provider/community/OnPremMongotBootstrapper.java` | Extract reusable process/Lucene/server helpers if useful; do not retain its MongoDB wiring in standalone composition. |
| `server/command/management/aic/*`, `management/util/IndexMapper.java`, `config/util/Invariants.java` | Share pure parsing/validation/conversion; add complete definition normalization/equivalence. |
| `lifecycle/*`, `replication/ReplicationManager*.java` | Ingestion adapter or source-neutral lifecycle interface; preserve drain/drop guarantees. |
| `config/manager/DefaultConfigManager.java`, recovery stagers, `StagedIndexesSwapper.java` | Mode-aware recovery and explicit standalone build/cutover eligibility. |
| `index/lucene/*` | Controlled generation-level refresh/checkpoint capability; retain mapping/document construction and partition behavior. |
| `server/grpc/*` | Register new services or extract shared TLS/server setup; standalone readiness and authorization. |
| `server/command/search/*` | Audit MongoDB-derived metadata assumptions if retaining internal query commands. |
| Root/community/deploy BUILD targets and launcher packaging | Add standalone executable, generated stubs, embedded-store dependency/native packaging, config/launcher. |
| `src/test/unit/java/com/xgen/mongot/standalone/...` | New contract, persistence, replay, lifecycle, and gRPC tests. |

There are existing protobuf and gRPC dependencies, but the existing raw-BSON command server does not demonstrate a ready-made generated service target. Add/verify Java gRPC code generation explicitly; a `proto_library` or the repository's BSON-proto generator alone is not the same thing as generated gRPC stubs.

When implementation changes third-party dependencies in `**/*MODULE.bazel`, follow `bazel/AGENTS.md` and run `make deps.update`. If editing a `.bzl` file, run `bazel mod tidy`. This guide itself does not modify build dependencies.

## 15. Staged implementation plan

### Phase A — Document the contract and prove the writer boundary

- Freeze ID equality, strict INSERT versus UPSERT, full-document replacement, retry rules, batch behavior, and acknowledgement levels.
- In a test harness, initialize a normal mongot Lucene generation, apply insert/upsert/delete events, commit, refresh, query, and reopen it.
- Include nested/embedded documents and a client-vector mapping, not only a flat text field.
- Verify there is no need to rewrite the analyzer/mapping/Lucene conversion layers.

**Exit:** duplicate IDs do not produce duplicate logical documents; replacement removes old fields/terms; delete removes all matching document-block data; reopening preserves committed results.

### Phase B — Standalone composition and local management

- Add the explicit standalone executable/config with no `syncSource` requirement.
- Implement local authoritative collection/index metadata and management request deduplication.
- Extract full mapping validation and add the standalone feature allowlist.
- Wire local reconciliation, physical journal restoration, and standalone readiness.
- Initially support new empty collections/indexes; explicitly reject populated-collection builds until Phase E.

**Exit:** with all MongoDB network access denied, an empty process starts, becomes management-ready, accepts create/list/get/drop, and retains index IDs/definitions after restart.

### Phase C — Durable document API

- Implement source store, logical mutation log, request manifests/outcomes, and ordered acceptance.
- Add generated Catalog/Document gRPC services, limits, transport security, and authorization.
- Implement strict INSERT uniqueness, full UPSERT, and DELETE.
- Attach ordered generation consumers and update indexing/limit/failure handling.
- Implement single and batch RPC completion barriers: no successful response before target indexing, with bounded in-flight requests/bytes and nonblocking server-side waits.

**Exit:** both single and batch clients wait until their mutations are indexed; successful writes survive a process kill even when Lucene has not committed; retried batches preserve original acceptance outcomes and do not resurrect superseded documents.

### Phase D — Checkpoints, refresh, recovery, and lifecycle safety

- Implement standalone checkpoint serialization, periodic commits, conservative replay, and watermarks.
- Add generation-specific `AwaitSequence` and a controlled blocking refresh barrier.
- Replace MongoDB status restoration and readiness assumptions.
- Implement drop/shutdown fencing, cursor cleanup, log retention, disk/backlog admission, and startup recovery.

**Exit:** crash-injection and multi-partition tests recover exactly the final accepted source state; no acknowledged event is skipped and no task uses a closed writer.

### Phase E — Populated-collection builds and mapping updates

- Add snapshot/build pinning, initial scan, ordered catch-up, durable build completion, and cutover coordination.
- Make staged swaps/recovery staging respect the standalone coordinator.
- Implement restart of interrupted builds and old-generation retirement.

**Exit:** a new index or mapping revision contains pre-existing documents, concurrent replacements, and concurrent deletes; old queries remain available until cutover; restart never promotes an incomplete build.

### Phase F — Production hardening and query boundary

- Add no-MongoDB process-level tests, backup/restore, schema upgrades, capacity benchmarks, and operational docs.
- Finalize the query adapter/result-source policy if a public standalone query API is required.
- Validate text/vector query behavior, feature flags, metrics, security, and launcher packaging.

**Exit:** the release can be installed, written to, queried through its documented interface, restarted, rebuilt, backed up, and restored without any MongoDB service or credentials.

## 16. Validation plan

### 16.1 Existing tests worth studying

Relevant examples are under `src/test/unit/java/com/xgen/mongot/`:

- `index/lucene/SingleLuceneIndexWriterTest.java`
- `index/lucene/MultiLuceneIndexWriterTest.java`
- `index/lucene/LuceneIndexingFailuresTest.java`
- `index/lucene/InitializedLuceneSearchIndexTest.java`
- `index/lucene/InitializedLuceneVectorIndexTest.java`
- `index/lucene/VectorIndexingAndQueryingTest.java`
- `config/manager/DefaultConfigManagerTest.java`
- `config/manager/ConfigInitializerTest.java`
- `catalog/InitializedIndexCatalogTest.java`

Use these patterns for writer initialization, mappings, querying, and restart behavior. Tests for the new ingestion boundary must not depend on a running MongoDB server or mock away the persistence behavior being tested.

### 16.2 Functional matrix

| Case | Expected result |
| --- | --- |
| Create index from API, restart | Same collection UUID/index ID, desired definition and physical-generation state retained. |
| Duplicate equivalent create / conflicting create | Existing ID returned / explicit conflict, using complete semantic equality. |
| Concurrent mapping changes with the same expected revision | Exactly one accepted; the other gets a CAS failure. |
| INSERT existing ID | `ALREADY_EXISTS`, no source/index replacement. |
| UPSERT new/existing ID | One final document, full replacement semantics. |
| DELETE missing/existing ID | Idempotent success; no stale indexed document. |
| Repeated request, including after later writes | Original outcome/sequence; no stale overwrite or resurrection. |
| Same request ID, different payload | Rejected, including unfinished batch tails. |
| Slow/paused target index, single and batch APIs | RPC remains pending, with no premature indexing-success response. |
| Many concurrent slow clients | Bounded in-flight memory/requests; bounded admission waits or pre-acceptance overload errors. |
| Deadline/disconnect after acceptance | Source/log retained and charged to backlog; retry resumes/waits without reapplying source state. |
| Mixed-success batch / interrupted batch | Ordered per-item outcomes preserved across retries. |
| Unsupported/malformed BSON, duplicate keys, oversized IDs | Explicit rejection before acceptance, never silent writer success. |
| Mapping mismatch / analysis failure / field limits | Documented omission or explicit blocked/failed generation; no false caught-up watermark. |
| VISIBLE wait | Newly acquired reader observes the accepted state; existing cursor snapshot behavior remains documented. |
| Index create after documents exist | Historical documents included by source snapshot. |
| Mapping rebuild with concurrent upsert/delete | Final new generation matches the collection at cutover and continues ordered application. |
| Index drop during write/build/commit/query | Safe fencing and cursor handling, no closed-writer race, source retained. |
| MongoDB-backed features requested | Rejected at validation, not discovered as an outbound connection during execution. |

### 16.3 Crash-injection matrix

Kill the process at each boundary below and restart from the same directory:

| Boundary | Required recovery |
| --- | --- |
| Before source transaction commit | No accepted mutation; same request can complete on retry. |
| After source/log/outcome commit, before RPC response | Retry returns original outcome; index replays accepted event. |
| Halfway through a batch | Prior outcomes retained, remaining items processed once. |
| After Lucene apply, before commit | Replay from conservative checkpoint; final state correct. |
| During a multi-partition commit | Conservative checkpoint plus ordered replay; no queryable partially recovered view. |
| After successful index commit, before local bookkeeping | Engine checkpoint remains authoritative for projection progress. |
| After refresh, before index commit | Visible-but-not-committed state can be reconstructed from durable source/log. |
| After desired index metadata commit, before config journal update | Reconcile durable desired intent on startup. |
| During source snapshot scan/catch-up | Restart or correctly resume staged build; old live generation preserved. |
| Before/after build-ready marker and journaled swap | No incomplete generation promoted; one deterministic live generation. |
| After durable drop intent, before directory deletion | Finish drop without resurrecting the resource. |
| During mutation-log pruning | Required suffix/pins remain valid, or full rebuild is explicitly selected. |

Also test disk-full/sync failures, corrupt or unsupported checkpoint/catalog schemas, unreadable desired definitions, queue saturation, expired retry windows, permission failures, and shutdown timeouts. Restore a backup into a fresh directory and validate both document state and retry/catalog identity semantics.

### 16.4 Prove the process is actually independent

Run an end-to-end test in an environment with no mongod/mongos installed and deny outbound MongoDB/network access except explicitly permitted services. Verify:

1. Startup needs no MongoDB URI, credentials, or topology.
2. Create a collection and a text index entirely over gRPC.
3. INSERT/UPSERT/DELETE, wait for visibility, and query through the supported adapter.
4. Create a vector index over the same existing documents and query it.
5. Kill/restart; verify accepted state, mappings, IDs, and readiness.
6. Update mappings while writes continue; verify old and new field behavior after cutover.
7. Drop an index, restart, and verify it stays dropped while source documents remain.
8. Backup/restore or delete only a recoverable projection in a controlled test; rebuild from local source.
9. Assert no replica-set discovery, metadata poller, synonym MongoDB client, materialized-view lease, or MongoDB heartbeat task was started.

## 17. Minimal prototype versus completion criteria

If the goal is only to validate feasibility, the shortest path is:

1. Separate bootstrap with locally persisted definitions and generated collection/index IDs.
2. Reuse standard index initialization with a small API-ingestion lifecycle adapter.
3. Add protobuf/gRPC Upsert/Delete and Create/Get/List/Drop endpoints.
4. Serialize writes; explicitly commit before success and refresh before claiming visibility.
5. Restrict to audited Lucene writers and one mapping generation per index.
6. Require caller reload for any rebuild/new historical mapping, and advertise INSERT as upsert unless uniqueness is separately implemented.

That prototype can be useful, but it is a different durability/rebuild contract from the recommended service. It must not claim WAL-backed acceptance, strict INSERT, automatic mapping rebuilds, or durable retry recognition it does not implement. A commit-per-write prototype also needs commit-failure/multi-partition recovery tests and cannot promise atomic batches or atomic writes across multiple indexes.

The recommended implementation is complete when:

- [ ] No MongoDB service, connection configuration, definition store, UUID lookup, heartbeat, or initial sync is required.
- [ ] All supported mapping creation/update/drop operations are durable API operations.
- [ ] INSERT, UPSERT, and DELETE have tested identity, ordering, retry, and error semantics.
- [ ] Single-document and batch RPCs block until indexed, with bounded admission and explicit deadline/failure behavior.
- [ ] Acknowledged source mutations survive a crash and drive deterministic replay/rebuild.
- [ ] Mapping changes and new indexes include existing documents and concurrent deletes.
- [ ] Physical index state, source state, and desired state have separate, recoverable responsibilities.
- [ ] Readiness, visibility, commits, backpressure, drops, and shutdown do not depend on MongoDB replication state.
- [ ] Unsupported MongoDB-dependent features are rejected explicitly.
- [ ] The supported query/result-document boundary is documented and works without mongod.
- [ ] Backup/restore, schema compatibility, security, and no-MongoDB acceptance tests pass.

**Bottom line:** keep mongot's indexing/query engine, replace its MongoDB-backed control and ingestion planes, and explicitly supply the durability/source-of-truth responsibilities that mongod previously fulfilled.
