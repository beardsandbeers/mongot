# Index joins for standalone mongot

**Available in standalone server 0.6.0 and later for search indexes.**

This guide adapts the original two-Lucene-index example to this repository's
standalone push server, BSON search expressions, mappings, and cursor lifecycle.
It covers joins across indexes and self-joins within one index.

The initial feature is an equality **semijoin**: return local documents
when at least one qualifying foreign document has a matching key. Returning
foreign documents, expanding rows, and combining relevance scores are separate
features. Use server 0.6.0 or later with the existing BSON SDK calls.

## 1. Project baseline and scope

The standalone server exposes gRPC without a MongoDB aggregation executor.
`QueryRequest.index_id` identifies the index whose documents are returned;
`query_bson` contains a mongot search expression. Keep that entry point.

Each index owns its documents and receives writes directly through `Upsert` and
`Delete`. `CreateIndex` takes a name, type, and mapping; names are instance-wide.
There is no shared source document store, automatic write fan-out, or replay log.
Applications write related content to each index themselves. `UpdateIndex`
creates an empty generation that callers must refill from their own source.

| Area | Existing behavior | Consequence for joins |
| --- | --- | --- |
| Runtime/build | Java 21 and Bazel | Uses the existing build, without a separate Maven application. |
| Lucene | Upstream modules at 10.4.0; core and join overridden by fork 10.4.0-5 | Verify against the pinned fork, rather than the original guide's 10.6.0 examples. |
| Catalog | `LocalIndexCatalog`, instance-wide index names, index ObjectIds, live generations | Resolve a foreign name once, then pin its stable index/generation identity. |
| Ingestion | Writes target one index; mapping updates create empty generations | Joins search indexed content; they do not fan out writes or trigger automatic backfill. |
| Search | `SearchQuery`, `JoinOperator`, and standalone join planning | Parses the foreign predicate separately from executing it against another index. |
| Token fields | Exact postings and `SortedSetDocValues` | Reuse these for string join keys, including scalars and arrays. |
| Identity | Internal `$meta/_id` uses BSON-encoded bytes | Token keys are not directly compatible with internal ID bytes. |
| Readers | Managed searcher references; optional local index partitions | Cover every partition and retain the correct snapshots. |
| Cursors | Snapshot-pinning results and index-scoped leases | Materialize membership once and preserve it through `GetMore`. |
| Authorization | QUERY role and index-ID allowlist | Require access to every participating index, including on continuation. |

References: [API schema](../src/proto/standalone/v1/standalone.proto),
[query service](../src/main/java/com/xgen/mongot/standalone/server/StandaloneQueryService.java),
[index catalog](../src/main/java/com/xgen/mongot/standalone/catalog/LocalIndexCatalog.java),
[dependency pins](../bazel/java/maven_search_query.MODULE.bazel), and
[client guide](../clients/README.md).

Supported scope:

- Equality membership between explicit string fields indexed as `token`.
- Cross-index joins and same-index self-joins in one standalone server.
- Any-shared-value semantics for arrays, without duplicate local results.
- Foreign predicates using supported search operators, with scores ignored.
- Filters and exclusions; ordinary local predicates retain their scoring.
- Multiple independent join predicates within server resource limits.
- Local scoring, sorting, counts, and facets through the existing query paths
  and feature flags. Existing drill-sideways defaults remain unchanged.

Defer foreign-document projection, outer/full joins, score propagation,
correlated expressions, composite keys, nested/recursive index joins,
remote servers, and vector joins. Native typed `_id` joins are deferred;
the first release uses explicit token keys.

## 2. Semantics and example data

For local document `d`, foreign predicate `Q`, and sets of indexed key values `K`:

```text
join(d) = exists foreign document f:
            Q(f) AND intersection(K(d.localField), K(f.foreignField)) is nonempty
```

Only local documents are returned. Foreign keys need not be unique: this also
supports many-to-many membership. One parent per contact is a data convention,
not an algorithm requirement.

```text
accounts_search index:
  { _id: "a1", accountKey: "a1", region: "bay area", industry: "aerospace" }
  { _id: "a2", accountKey: "a2", region: "new york", industry: "aerospace" }
  { _id: "a3", accountKey: "a3", region: "bay area", industry: "finance" }

contacts_search index:
  { _id: "c1", accountKey: "a1", title: "Principal Software Architect" }
  { _id: "c2", accountKey: "a2", title: "Solutions Architect" }
  { _id: "c3", accountKey: "a1", title: "Aerospace Engineer" }
  { _id: "c4", accountKey: "a3", title: "Cloud Architect" }
```

Search contacts for `architect` whose account is in `bay area` and `aerospace`.
Only `c1` qualifies. Conceptually this is SQL `WHERE EXISTS`, without returning
contact/account pairs.

## 3. BSON syntax

Use `join` inside `compound.filter`, with familiar equality-join field names and
the existing `operator` convention for a nested search predicate.

### Cross-index search

The outer request selects the contacts index. Its `query_bson` is:

```json
{
  "compound": {
    "must": [
      { "text": { "path": "title", "query": "architect" } }
    ],
    "filter": [
      {
        "join": {
          "from": "accounts_search",
          "localField": "accountKey",
          "foreignField": "accountKey",
          "operator": {
            "compound": {
              "filter": [
                { "equals": { "path": "region", "value": "bay area" } },
                { "equals": { "path": "industry", "value": "aerospace" } }
              ]
            }
          }
        }
      }
    ]
  }
}
```

`accounts_search` is the instance-wide name of the foreign search index. The
outer request identifies `contacts_search` by its index ID. The existing SDK
request shape carries the join expression:

```python
# `query` is the expression above; use your configured index names and IDs.
query["compound"]["filter"][0]["join"]["from"] = accounts_index_name
response = client.search(api.QueryRequest(
    index_id=contacts_index_id,
    query_bson=encode_bson(query),
    batch_size=10,
))
```

No new RPC or protobuf field is needed. Protobuf request fields keep `snake_case`, while
fields inside BSON use the existing `camelCase` convention.

| Field | Contract |
| --- | --- |
| `from` | Optional, nonblank index-name string. Omitted means the exact current index and its pinned generation/readers. |
| `localField` | Required literal field path resolved against the outer index. |
| `foreignField` | Required literal field path resolved against the foreign index. No implicit `_id` default. |
| `operator` | Required foreign search operator subtree; paths and analyzers belong to the foreign index. |

Resolve an explicit `from` by exact, case-sensitive name in `LocalIndexCatalog`.
Names are unique across the standalone instance, so no namespace qualifier or
ambiguity rule is needed. A missing name is `NOT_FOUND`; a target that is not a
search index is `INVALID_ARGUMENT` in the first release.

Resolve the name once during planning, authorize its index ID, and retain its
stable index/generation identity. Cursor continuation must not resolve the name
again or switch to a dropped-and-recreated index with the same name. Name lookup
is internal query planning; it must not require the caller to invoke the catalog
RPCs or acquire the CATALOG role.

Require a predicate initially, rather than silently defaulting to a full foreign
scan. It can be a `compound`. It is not a complete `SearchQuery`: reject foreign
`index`, sort, pagination, counts, collectors, highlights, stored-source options,
and nested index joins. Collect **all** qualifying keys; a foreign top-k or
`batch_size` limit would change the result incorrectly.

Initially accept joins in `compound.filter` and `compound.mustNot`, including
non-scoring compound subtrees under those clauses. Reject scoring `must`/`should`
placements and a bare root `join`. A join-only search uses a root `compound` with
only `filter`. Reject `score` on `join`; foreign operator scores are ignored.

### Same-index self-join

Find employees whose manager works in Berlin. Omit `from` to use the current
employees index on both sides:

```json
{
  "compound": {
    "filter": [
      {
        "join": {
          "localField": "managerKey",
          "foreignField": "employeeKey",
          "operator": {
            "equals": { "path": "office", "value": "Berlin" }
          }
        }
      }
    ]
  }
}
```

Map both keys and `office` as tokens. An explicit `from` string can also name
the outer index. Both forms reuse exactly the same reader snapshots. Naming
another index performs a cross-index join, even if the application writes similar
documents to both; the indexes have independent documents, mappings, and visibility.

The local and foreign field names may be identical. A document may match itself
when its keys and foreign predicate match; there is no implicit self-exclusion.
This is one-hop existence, not recursive manager traversal.

### Reverse direction and exclusion

To return accounts having architect contacts, query the accounts index and join
to the contacts index. Use `accountKey` on both sides and a foreign `text`
operator over `title`. There is no separate parent versus child join syntax.

Putting the same `join` object inside `compound.mustNot` means no qualifying
foreign match exists. This includes documents with missing keys or orphaned
references. Add a local existence predicate when missing keys should be excluded.

## 4. Mappings and key equality

The example uses explicit `accountKey` fields to stay within the existing token
representation. These are existing mapping shapes.

Accounts search index definition:

```json
{
  "mappings": {
    "dynamic": false,
    "fields": {
      "accountKey": { "type": "token", "normalizer": "none" },
      "region": { "type": "token", "normalizer": "none" },
      "industry": { "type": "token", "normalizer": "none" }
    }
  }
}
```

Contacts search index definition:

```json
{
  "mappings": {
    "dynamic": false,
    "fields": {
      "accountKey": { "type": "token", "normalizer": "none" },
      "title": { "type": "string" }
    }
  }
}
```

`dynamic: true` alone does not establish the token-key contract. Resolve both
paths as explicit token fields. Use
`FieldName.TypeField.TOKEN.getLuceneFieldName(path, Optional.empty())`; do not
pass raw BSON paths to Lucene or assume token and internal ID field names match.

`IndexableFieldFactory` writes tokens to exact postings and
`SortedSetDocValuesField`, even for scalars. The original guide's single-valued
`SortedDocValues` collector and `JoinUtil(..., false, ...)` do not apply here.

Equality rules:

- Compare indexed token bytes. Require matching normalizers; recommend `none`
  for identifiers. Lowercase normalization deliberately makes keys
  case-insensitive. Do not normalize collected bytes a second time.
- Scalars contribute one key; arrays contribute their indexed string values.
  Any shared value qualifies, with one result per local document.
- Missing, null, empty arrays, and other BSON types contribute no token key.
  A missing token mapping is a query error, not an empty join.
- An empty string is an ordinary key when present in the token index.
- No implicit number/ObjectId/string conversion, uniqueness enforcement,
  referential integrity, or cascading deletion.

**Length caveat:** token ingestion truncates long strings in
`addSortableStringFieldIfNeeded`. Indexed-token joins inherit that behavior:
distinct overlong source values can collapse to one key. Applications using
tokens as identity keys must bound their canonical keys below the ingestion
limit. Lossless raw-value equality would need a mapping/ingestion contract that
rejects overlong join keys and accounts for existing truncated data. Collecting
keys at query time cannot recover that information.

**`_id` needs a separate design.** The internal ID is BSON-encoded using
`LuceneDocumentIdEncoder`; ID DocValues availability depends on index
capabilities. A UTF-8 token `"a1"` cannot directly match the internal encoding of
`_id: "a1"`. Explicitly mapping `_id` as a token could support string joins
through that token representation, but does not supply typed-ID joins. Native
ID joins need a typed key adapter, a supported foreign-key mapping, and a rule
for BSON int32 versus int64 equality.

References: [field construction](../src/main/java/com/xgen/mongot/index/lucene/document/single/IndexableFieldFactory.java),
[field naming](../src/main/java/com/xgen/mongot/index/lucene/field/FieldName.java),
[token definitions](../src/main/java/com/xgen/mongot/index/definition/TokenFieldDefinition.java), and
[ID encoding](../src/main/java/com/xgen/mongot/index/lucene/util/LuceneDocumentIdEncoder.java).

## 5. Integration and execution

`StandaloneJoinPlanner` handles foreign index access outside BSON parsing and
local query factories. `SearchIndexReader.QuerySnapshot` provides managed
reader acquisition, token-key collection, and execution on the same snapshots.

```text
StandaloneQueryService.search(QueryRequest)
  parse and validate join placement / supported query forms
  resolve foreign names to stable index IDs; authorize indexes; validate mappings
  pin live generations and managed partition searchers
  StandaloneJoinPlanner, for each join:
    compile foreign operator using the foreign index's query factory
    collect all matching keys across every foreign partition
    union into one immutable, bounded membership set
  open local cursor with the already pinned local searchers and internal plan
    compile membership against every local partition
    apply it before hit, count, and supported collector processing
  GetMore continues the initial local snapshots and materialized membership
```

The service parses `SearchQuery`, prepares membership, and passes the pinned
local snapshot through `CursorQuery.Search` to `runtime.cursors().newCursor`.
`CursorFactory` uses that snapshot instead of acquiring another local reader.
Ordinary searches retain the existing reader-acquisition path.

`LocalIndexCatalog.getIndexByName` resolves names under the catalog lock. The
planner retains IDs before acquiring readers. A dropped dependency causes failure
instead of a second lookup against a replacement with the same name.

`JoinOperator` describes the wire expression; `MaterializedJoinOperator` holds
immutable indexed-token strings internally. Serialization retains the original
expression without serializing membership. Local optimizer and scope visitors
treat a join as a leaf; its foreign predicate uses its own query factory and
mapping context. Membership is never expanded into a public BSON `in` expression.

`MultiLuceneSearchIndexReader` is an important boundary: union foreign keys once
across **all** foreign partitions, then apply that set to every local partition.
Pairing partition 0 with partition 0 misses valid matches. For self-joins, reuse
the same pinned partition snapshot set; matching documents need not share a
partition or segment.

Initially reject `returnScope`, embedded-document join placement, and foreign
`embeddedDocument`/`hasRoot`/`hasAncestor` predicates. These existing block-join
operators describe relationships within one BSON document. Reject index
joins in vector entry points/operators until separately supported.

## 6. Lucene strategy and ordinals

All strategies first search foreign documents and materialize distinct keys.
Use managed searcher references; do not open independent `FSDirectory` readers.

| Strategy | Local membership query | Role |
| --- | --- | --- |
| A: postings | `TermInSetQuery` | Baseline with a bounded custom collector. |
| B: DocValues | `SortedSetDocValuesField.newSlowSetQuery` | Compare for highly selective local predicates. |
| C: both | `IndexOrDocValuesQuery(A, B)` | Reuse the pattern in `InQueryFactory.stringQuery`. |

The implementation uses bounded foreign-key collection and A. B/C remain
alternatives for future tuning; the development comparison in section 8 includes
all three. Algorithm selection is internal. Empty sets become `MatchNoDocsQuery`.

```java
// Equivalent adaptive alternative; production currently uses postings alone.
Query membership = keys.isEmpty()
    ? new MatchNoDocsQuery("No foreign join keys")
    : new IndexOrDocValuesQuery(
        new TermInSetQuery(localTokenField, keys),
        SortedSetDocValuesField.newSlowSetQuery(localTokenField, keys));
// Attach at the parsed filter/exclusion position before collecting results.
```

Mutable DocValues iterators must be leaf/execution-local. Retained `BytesRef`
values need owned storage, such as deep copies, rather than iterator buffers.

Lucene's term-based `JoinUtil.createJoinQuery` is a useful reference baseline.
For token fields use `multipleValuesPerDocument: true`, selecting sorted-set
collection. It materializes keys internally before returning a query. A bare
utility call does not provide the required budgets, cancellation, and partition
union. See the [10.4.0 implementation](https://github.com/apache/lucene/blob/releases/lucene/10.4.0/lucene/join/src/java/org/apache/lucene/search/join/JoinUtil.java)
and verify against this project's fork before implementation.

No shared global ordinal map is required. One key can have different local
ordinals in any two segments, including in the same index. Transfer values:
postings match them directly, and DocValues membership resolves them in each
local segment. The single-searcher global-ordinal join overload is a different
algorithm, not an automatic cross-index map. Defer custom shared dictionaries
and ordinal caches until benchmarks justify them.

References: [membership factory](../src/main/java/com/xgen/mongot/index/lucene/query/InQueryFactory.java),
[Lucene reader](../src/main/java/com/xgen/mongot/index/lucene/LuceneSearchIndexReader.java), and
[partitioned reader](../src/main/java/com/xgen/mongot/index/lucene/MultiLuceneSearchIndexReader.java).

## 7. Consistency, permissions, and resource bounds

### Snapshots and cursors

- Pin a live generation and managed reader snapshot set per dependency. Reuse
  local snapshots exactly when the foreign index is the outer index.
- Different indexes own independent documents and reader views. Successful
  `VISIBLE` writes apply to one index; separately writing/refreshing both sides
  does not create an atomic cross-index transaction snapshot.
- Mapping updates create empty generations. Fresh joins use query-ready live
  generations and see only the content the application has ingested into them;
  readiness does not imply that application-level reingestion is complete.
- Materialize keys once. Release foreign searchers after collection if the plan
  owns only copied bytes. Keep membership and local readers for the cursor.
- `GetMore` never reruns the foreign predicate. Foreign changes affect fresh
  searches; existing cursors retain their initial membership.
- Record every dependency's index and generation IDs in the lease. Invalidate a
  join cursor when any dependency is dropped or its generation is retired by a
  mapping update. Extend the existing outer-index drop/generation cleanup to
  foreign dependencies, including checks for invalidation races before serving
  a batch. Normal reader refreshes preserve pinned results; never retarget a
  cursor to a replacement generation or rerun its foreign predicate.
- `QueryResponse.generation_id` describes the outer index only. Foreign fences
  or dependency metadata would be separate additions if needed.
- Error, cancellation, kill, expiry, and shutdown release readers, key memory,
  and admission permits, including after partial acquisition.

Scoring, sorting, highlighting, and stored-source output describe local
documents. Apply membership before top-k and counts; filtering returned batches
would produce incorrect rankings and totals. Every supported facet/aggregation
path must consume the same plan. Explicitly reject combinations not yet wired
through and tested.

### Authorization

Require the QUERY role and access to every dependency index before running
foreign predicates. `StandaloneAuthInterceptor.requireIndex` and
`StandaloneConfig.Principal.allows` enforce the existing index-ID grants. The
query service and planner check all dependencies for joins, including on
`GetMore` after membership has been materialized. A grant for one index does not
authorize a join to another.

Foreign matches reveal information even when foreign documents are not returned.
The foreign index therefore requires authorization too. A field called
`tenantId` is not automatically enforced by the existing index allowlist;
do not assume document-level tenant isolation.

### Limits and errors

The server applies these fixed admission limits without extra configuration:

| Limit | Value |
| --- | --- |
| Independent predicates per query | 8 |
| Distinct keys across predicates | 100,000, also constrained by memory |
| Estimated build/retained memory per query | 32 MiB |
| Estimated memory across concurrent joins and their cursors | 256 MiB |
| Foreign-work checkpoints across predicates | 10,000,000 |

Memory reservations conservatively account for query metadata, strings, hash
entries, immutable lists, and term-query/rewrite copies per partition and facet.
They are admission estimates,
not a measurement of JVM heap or RSS. Cursor-retained membership keeps its
reservation until exhaustion, kill, invalidation, expiry, or shutdown. Deadline
and cancellation checks run during foreign collection and local Lucene execution;
each `GetMore` installs its current invocation's cancellation context.

`GetStatus` exposes `standalone.joins.reservedBytes`, `standalone.joins.keys`,
`standalone.joins.foreign`, and `standalone.joins.prepare`. These track reserved
memory, successful key counts, foreign collection time, and complete planning
time. Existing query/RPC timers cover local execution and overall request time.

Never truncate the foreign result/key set to fit a budget. Fail before returning
a partial answer.

| Condition | gRPC status |
| --- | --- |
| Invalid syntax, placement, mapping, normalizer mismatch, target index type, or unsupported combination | `INVALID_ARGUMENT` |
| Missing/dropped dependency, subject to normal authorization handling | `NOT_FOUND` |
| Index access denied | `PERMISSION_DENIED` |
| No query-ready generation | `UNAVAILABLE` |
| Explicit generation fence no longer satisfied | `FAILED_PRECONDITION` |
| Key, memory, or work limit exceeded | `RESOURCE_EXHAUSTED` |
| Deadline or caller cancellation | `DEADLINE_EXCEEDED` / `CANCELLED` |

Foreign key sets and local membership are not cached across requests. Pinned local
searchers disable Lucene query caching so membership terms retain the cursor's
bounded lifetime. Later key-set caches must bind to foreign
reader identities, generation, predicate, field/normalizer semantics, and
authorization context. Ordinal caches also need local reader/leaf and membership
identity; integer ordinals are not stable across refreshes/merges.

## 8. Validation and algorithm comparison

Implementation entry points:

1. Typed parsing/serialization: `JoinOperator` and `MaterializedJoinOperator`.
2. Name resolution, authorization, budgets, and immutable membership:
   `LocalIndexCatalog` and `StandaloneJoinPlanner`.
3. Managed readers and partition-wide execution: `SearchIndexReader.QuerySnapshot`,
   `LuceneSearchIndexReader`, and `MultiLuceneSearchIndexReader`.
4. Cursor transfer, paging, cancellation, and dependency invalidation:
   `CursorQuery.Search`, `CursorFactory`, and `StandaloneQueryService`.
5. Usage: existing `QueryRequest.query_bson`; no generated schema changes.
6. Local membership: `JoinQueryFactory` uses postings after comparing A/B/C below.

Relevant checks from the repository root:

```bash
bazel test --test_output=errors \
  //src/test/unit/java/com/xgen/mongot/standalone/... \
  //src/test/unit/java/com/xgen/mongot/index/query/... \
  //src/test/unit/java/com/xgen/mongot/cursor/... \
  //src/test/unit/java/com/xgen/mongot/index/lucene/query/... \
  //src/test/unit/java/com/xgen/mongot/index:QueryMetricsRecorderTest \
  //src/test/unit/java/com/xgen/mongot/index/lucene:LuceneSearchIndexReaderTest \
  //src/test/unit/java/com/xgen/mongot/index/lucene:MultiLuceneSearchIndexReaderTest
bazel build \
  //src/main/java/com/xgen/mongot/standalone:MongotStandalone \
  //src/main/java/com/xgen/mongot/community:MongotCommunity
```

Development verification on 14 November 2026 passed all 285 test targets above,
including 24 join behavior/reader tests and 10 join parser/serialization tests.
Both executables built successfully. A separate launched standalone process also
returned the guide's expected result over gRPC, observed a foreign-document
update, and paged a self-join. Live MongoDB ingestion alongside push ingestion
was not exercised by these checks.

Regression coverage and cases to preserve:

- The contact example returns only `c1`; reverse joins return accounts once.
- Same-index self-joins and cross-index joins, including independently written
  documents in different segments and local partitions.
- Instance-wide index-name lookup, case-sensitive matching, missing names,
  unsupported target types, and name reuse after drop without cursor retargeting.
- Scalar/array keys, duplicates, missing/null/wrong types, empty strings, empty
  foreign results, normalizers, Unicode, and overlong-key collisions.
- Boolean composition, exclusions, multiple predicates, and rejected nested,
  scoring, embedded, and vector forms.
- Local relevance, pre-top-k filtering, counts, and supported collectors.
- Foreign document upserts/deletes between pages, refreshes, merges, mapping
  updates that create empty generations, explicit reingestion, dependency
  drops/generation retirement, cursor expiry, and kill.
- Access denied on the foreign side, permission changes before `GetMore`,
  budget/deadline failures, cancellation, and cleanup after partial acquisition.

Measure foreign search/key collection, membership construction/rewrite, local
execution, and total time separately. Vary key cardinality, local selectivity,
fan-out, segments/partitions, key length, concurrency, and warm/cold readers.
Report p50/p95 latency, peak/retained memory, allocations, and cancellation latency.
Include foreign preparation in every comparison.

### Development comparison (14 November 2026)

A Lucene-only warm-reader comparison used the pinned `10.4.0-5` core, Java 21.0.4,
10,000 accounts in one segment, and 100,000 contacts in ten segments. Each
strategy used the same pinned readers and included foreign search/key collection,
membership construction/rewrite, and local top-10 search. Counts were checked
for equivalence before timing. There were 20 warmups and 40 measured runs per
case; query caches were disabled. This excludes gRPC, authorization, and server
admission bookkeeping and is not a server latency guarantee.

Times below are total p50 / p95 milliseconds. Broad local predicates match
100,000 candidates; selective predicates match 100.

| Foreign keys / local predicate | A: postings | B: DocValues | C: adaptive |
| --- | --- | --- | --- |
| 10 / broad | 2.510 / 2.888 | 3.170 / 3.606 | 2.124 / 2.418 |
| 10 / selective | 1.887 / 2.201 | 0.910 / 1.105 | 1.915 / 2.218 |
| 5,000 / broad | 11.385 / 13.967 | 53.925 / 56.175 | 12.041 / 13.602 |
| 5,000 / selective | 11.343 / 12.458 | 53.327 / 55.506 | 53.648 / 55.226 |

For 5,000 keys and selective local candidates, median thread allocation was
about 4.91 MiB for A, 18.60 MiB for B, and 18.79 MiB for C. These cases support
postings as the initial baseline while showing a small-key selective case where
DocValues wins. Rebenchmark with production data before changing the strategy.

## 9. Agreed initial syntax and scope

| Decision | Initial choice | Rationale / consequence |
| --- | --- | --- |
| Result semantics | Existence filter returning local documents | Enrichment or joined rows require an output/projection design. |
| Operator name | `join` | `hasMatch` / `existsIn` emphasize existence; `lookup` suggests enrichment. |
| Foreign target | `from: "indexName"` | Resolve the instance-wide name once, then retain stable index/generation identities. |
| Self-join | Omit `from` | Reuses the current index and snapshots without a name lookup. Its explicit name also works. |
| Key names | `localField` / `foreignField` | `path` / `from.path` uses search vocabulary but makes direction less familiar. |
| Foreign predicate | Required `operator` subtree | `query` suggests a full request; omission would implicitly scan all foreign documents. |
| Keys | Explicit token strings first | Native typed `_id` removes the shadow key but needs encoding/mapping work. |
| Placement | Filter/exclusion positions first | Scoring positions need defined constant-score or foreign-score behavior. |

The first release uses explicit token keys such as `accountKey`. Native typed
`foreignField: "_id"` joins are a later extension requiring the adapter described
in section 4.
