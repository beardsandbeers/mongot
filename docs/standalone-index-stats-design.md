# Index and field statistics API design

Status: Implemented for standalone 0.5.0. Optional sampling remains future work.

Add per-index gRPC statistics for live document count, index size, and average storage per
document. Add a separate, bounded field statistics API to explain which indexed fields consume
storage and memory. Support both search and vector-search indexes, including dynamic mappings,
multiple types at one path, embedded documents, partitions, and quantized vectors.

Per-field attribution is feasible for some components and requires estimates or additional codec
instrumentation for others. Report directly attributable bytes, shared costs, and unknown costs
separately. Distinguish accounted heap from desired vector working-set size; neither is a promise
of a field's resident RAM usage or the savings from removing that field.

## Current behavior

`OperationsService.GetStatus` exports raw meters with index and generation labels, including
`index.stats.indexSizeBytes` and `index.stats.numLuceneDocs`. It requires the `catalog` role and
access to all indexes. There is no typed per-index statistics response or average-size metric.

The existing [document counts](../src/main/java/com/xgen/mongot/index/DocCounts.java) distinguish
root documents from Lucene child documents and deleted records. Existing
[disk statistics](../src/main/java/com/xgen/mongot/index/lucene/backing/DiskStats.java) scan a
generation's directory and can include temporary or retained files. Their `requiredMemoryBytes`
uses vector file sizes and compound-file heuristics. Preserve that legacy meter, but do not
present it as measured heap usage in the new API.

`GetIndexMappings` already decodes typed Lucene field names into public mappings. Reuse its field
name conventions for attribution; `FieldInfos` alone contains neither per-field disk sizes nor
complete memory ownership information.

## Questions the API should answer

- How many live source documents does this index currently represent?
- How many bytes do its current searchable segments occupy, and how much space is its directory
  using, including merge and retention overhead?
- What is the average storage per live source document?
- Which fields and indexed representations account for the most storage or known heap usage?
- How large is a vector field's desired off-heap working set, including its graph and applicable
  quantized data?
- Which costs are shared, estimated, or unavailable, and how fresh are the measurements?

## Index statistics contract

Statistics describe one ready, active generation. Return `index_id`, `generation_id`,
`active_revision`, a `snapshot_id`, and collection timestamps. Do not silently combine the old
active generation with a newly requested mapping or a retiring generation.

| Metric | Definition |
| --- | --- |
| `document_count` | Live root/source documents represented in the pinned reader snapshot. Excludes embedded children and deleted documents. Counts current indexed documents, not cumulative accepted upserts. |
| `lucene_document_count` | All live Lucene documents, including embedded children. |
| `deleted_lucene_document_count` | Sum of `maxDoc - numDocs` across the same pinned readers. These records can still consume storage until merges reclaim them. |
| `index_size_bytes` | Sum of unique file lengths referenced by the pinned segment snapshots, including segment metadata and update files. For compound segments, count the physical container once. Excludes unrelated commit files, catalog metadata, temporary merge output, and files retained only for other readers. |
| `directory_size_bytes` | Separately sampled file lengths in this generation's Lucene partition directories, including temporary and retained files. Excludes other generations and the shared catalog. |
| `average_index_bytes_per_document` | `index_size_bytes / document_count`, absent when the count is zero. |
| `average_directory_bytes_per_document` | `directory_size_bytes / document_count`, absent when the count is zero. Useful for current disk budgeting; numerator and denominator have different sampling boundaries. |

Both size measurements use file lengths, not filesystem allocated blocks, sparse-file savings,
container image size, or volume capacity. Unmerged deletes, segment headers, and fixed index
overhead affect the averages. An average is not the incremental cost of adding one document.

Read root counts from the pinned readers, including the embedded-root marker when applicable.
Do not combine a writer's current count with a cached embedded-root count. Define behavior for
sparse documents by whether their root document is actually present in the snapshot; no source
document store exists from which to reconstruct a separate historical ingestion count.

The directory walk and memory sampling are not atomic with the reader snapshot or concurrent
merges. Give them separate timestamps and quality information. Do not assert a strict equality
or ordering between directory size and segment size measured at different instants.

## Per-field feasibility

| Component | Proposed reporting | Limitation |
| --- | --- | --- |
| Vector values and HNSW graphs | Per-field desired off-heap bytes by codec category; attributable disk ranges where a codec adapter verifies ownership. Include applicable raw and quantized representations separately. | Desired working-set bytes are not resident RAM or automatically a complete disk-size measurement. Unsupported codecs must remain explicit. |
| Postings, terms, positions, doc values, norms, points, and term vectors | Attribute verified exclusive file ranges where codec metadata exposes them. Report shared producer/file costs separately. | A per-field format can still group several fields into one file. Term counts or file suffixes alone do not establish ownership. |
| Stored fields and stored source | Measure the shared physical storage. Optionally estimate allocations using a separately labeled sampling analysis. | Compression operates across blocks. Stored source can be one BSON blob containing many source fields; exact compressed ownership by source path is generally unavailable. |
| Reader objects and field-specific caches | Accounted heap for components whose APIs or application instrumentation identify an owner. | Reported object sizes can be estimates. Some resources have only a segment/index owner; many query-cache entries depend on multiple fields. |
| Writer buffers and temporary query/native allocations | Per-index accounting where supported; field-level tracking only after adding explicit ownership instrumentation. | Existing total buffer accounting does not imply an available per-field breakdown. |
| Mapped files and OS page cache | Desired vector working set now; optionally identifiable mapped byte ranges as a separate diagnostic later. | Mapping length is not resident physical memory. Portable exact per-field RSS is outside this proposal. |

Lucene's [`KnnVectorsReader.getOffHeapByteSize(FieldInfo)`](https://lucene.apache.org/core/10_4_0/core/org/apache/lucene/codecs/KnnVectorsReader.html#getOffHeapByteSize(org.apache.lucene.index.FieldInfo))
provides a per-field working-set breakdown. Its default result can be empty, and its values describe
the capacity needed to load those structures, rather than current RAM consumption. The local
[binary quantization reader](../src/main/java/com/xgen/mongot/index/lucene/quantization/Mongot01042BinaryQuantizedFlatVectorsReader.java)
already combines raw and quantized categories, and native/flat wrappers forward this API.

[`Accountable`](https://lucene.apache.org/core/10_4_0/core/org/apache/lucene/util/Accountable.html)
exposes object accounting and optional child resources, not a universal field-to-memory table.
Use it only where ownership and inclusive/exclusive accounting are known. The existing Bloom
producer registry and facet-state cache are potential instrumentation points; their current
aggregate metrics must not be treated as a complete per-field breakdown.

Lucene's [stored-fields format](https://lucene.apache.org/core/10_4_0/core/org/apache/lucene/codecs/lucene90/Lucene90StoredFieldsFormat.html)
compresses blocks of documents with shared dictionaries. The local stored-fields codec customizes
that compression. Splitting the compressed total in proportion to source BSON lengths would be
an allocation estimate, not a measurement of compressed bytes owned by each field.

## Attribution rules

### Logical fields and physical representations

Identify a logical field using the same canonical path semantics as search and mapping export.
Under that field, retain separate representation identities for mapping type, string multi-field
name, and embedded root. A string and number at `price`, or root and embedded representations of
one path, must not overwrite one another.

Group numeric/date storage variants and string multi-fields under their logical field while
retaining component details. A document container does not automatically own all descendant
fields. Default totals are direct costs; any subtree rollup is a separate view and must not be
summed together with its descendants.

Put maintenance fields such as `$meta/_id`, live-doc bits, segment headers, and unassignable
embedded markers into named system/shared buckets. Do not confuse an explicitly mapped source
`_id` with the internal identity index. Include explicitly configured but unmaterialized fields,
and fields still present in retained snapshot segments; describe their mapping/observed status.

### Storage ledger

Build an ownership ledger keyed by partition, segment, physical file, and byte range. Count each
range once and distinguish three disjoint categories:

```text
index_size_bytes = sum(field.attributed_disk_bytes)
                 + shared_disk_bytes
                 + unattributed_disk_bytes
```

Shared bytes have a known component and multiple owners, or belong to the index itself.
Unattributed bytes lack a supported ownership breakdown. Neither category is silently spread
across user fields. Preserve this equality for every completed storage snapshot.

A compound-file adapter must resolve inner entries to the physical container and account for
container overhead once. Never add virtual inner-file lengths on top of `.cfs` length. Vector
working-set sizes enter the disk ledger only when a codec adapter independently establishes the
corresponding physical ranges. A codec reporting a shared total for several fields is counted
once in the shared bucket.

Reject overlapping or out-of-bounds ownership claims from an adapter and leave the affected
component unattributed with a reason. Do not hide invalid attribution by clipping totals.

Report coverage as `attributed_disk_bytes / index_size_bytes` when the denominator is nonzero.
Optional allocated estimates may help rank fields, but belong in a separate estimated view and
must not participate in the exact ledger.

For example, a 256 MiB snapshot could have this ledger:

| Owner | Attributed storage |
| --- | ---: |
| `title` | 32 MiB |
| `embedding` | 128 MiB |
| Known shared components | 64 MiB |
| Components without supported attribution | 32 MiB |

Field attribution coverage is 62.5%. With one million live root documents, average index storage
is about 268.44 bytes per document. This does not assign the remaining 96 MiB to either field or
predict the exact savings from removing `title`.

### Memory domains

Return separate measurements for `accounted_reader_heap_bytes`,
`accounted_writer_buffer_heap_bytes`, `accounted_cache_heap_bytes`, and
`desired_vector_off_heap_bytes`. Add observed native allocations only when an allocator can
associate them with the selected generation and field.

Do not sum desired working-set bytes into a metric named actual memory usage. Do not promise
that field memory totals reconcile to JVM heap, process RSS, or container memory. Readers,
analyzers, allocator overhead, caches, and temporary work can be shared or outside the measured
scope. Query traffic, warmup, and concurrent indexing also change memory consumption.

Deduplicate shared reader cores and resources by identity. Do not sum an inclusive parent
`ramBytesUsed()` value and its children. If exclusive ownership cannot be established, keep the
parent measurement in a shared bucket rather than inventing a per-field decomposition.

### Quality and missing values

Every byte measurement carries an optional value, method, completeness, and component coverage:

```text
ByteMeasurement {
  optional uint64 value;
  method: FILE_RANGE | RESOURCE_ACCOUNTING | CODEC_WORKING_SET | MODEL_ESTIMATE | UNAVAILABLE;
  completeness: COMPLETE | PARTIAL | UNAVAILABLE;
  included_components[];
  omitted_components[];
}
```

`PARTIAL` values contain only the supported components and identify what is omitted. A missing
value means unavailable; zero means a supported measurement returned zero. An empty vector-reader
map is not proof that the field needs zero memory. Codec working-set reports remain distinct
from resource accounting even when their reported size is exact for a specific structure.

## Proposed gRPC surface

Add two methods to `CatalogService`, using its existing `catalog` role but enforcing access to the
requested index rather than requiring the wildcard grant used by `GetStatus`:

```proto
rpc GetIndexStats(GetIndexStatsRequest) returns (IndexStats);
rpc GetIndexFieldStats(GetIndexFieldStatsRequest) returns (IndexFieldStatsResponse);
```

The complete wire schema is in [standalone.proto](../src/proto/standalone/v1/standalone.proto).

`GetIndexStatsRequest` contains `index_id`, an optional `generation_id` fence, and `refresh`.
`IndexStats` contains the identity, timestamps, counts, sizes, averages, and separate memory
measurements defined above. Use protobuf `uint64` for counts/bytes and optional doubles for
averages. Preserve decimal-string handling for `uint64` in the TypeScript client.

`GetIndexFieldStatsRequest` adds optional exact path filters, `page_size`, and `page_token`.
Return field identities and representations, storage and memory measurements, shared/system and
unattributed buckets, coverage, and the matching `IndexStats` for that analysis snapshot. A
separate `GetIndexStats` call may observe a different snapshot; the included summary is the one
to use for reconciliation and averages.

Field collections can return `COLLECTING` with a retry delay and no result when no cached result
exists. When refreshing a cached result, keep that snapshot `READY` and report its age and
`refresh_in_progress` separately. `READY` pages are immutable and sorted by canonical
path/representation. Page tokens bind to the snapshot, generation, filter, page size, and
authenticated principal; never join pages
from different snapshots. Reject mismatched tokens and return an explicit expired-snapshot error.
Repeat role and index-grant checks on every poll and page; a token does not grant access by itself.
Refresh applies to an initial collection request, never to the snapshot selected by a page token.

Return snapshot-wide attributed, shared, and unattributed storage totals on every page. Path
filters and pagination select field rows; they do not turn known excluded fields into unknown
costs or change the global coverage denominator. The current page's field subtotal is not the
snapshot-wide attributed total. Filters can reduce result size without necessarily reducing
global collection work.

There is no expensive full-data profiling mode hidden behind `refresh`. Refresh requests renew
the bounded measurement described here, not a term scan, heap dump, integrity check, full merge,
or reindex. General sampling/profiling would require a separately budgeted diagnostic operation.

## Collection and lifecycle

1. Resolve and authorize the index and generation. Reject pending or transitioning generations.
2. Acquire a proposed generation statistics lease that protects both reader references and the
   generation's directory lifetime. A searcher reference alone is insufficient protection against
   lifecycle cleanup of the entire directory.
3. For a refreshed snapshot, coordinate reader refresh and capture across all partitions with the
   existing ingestion barrier, so counts are taken after a complete write boundary. Refresh can
   flush buffered data and incur I/O; it does not request a durable commit. Measure this barrier
   cost, especially because the current ingestion coordinator is shared across indexes.
4. Release the ingestion barrier before directory walks or codec analysis. Inspect pinned
   readers for counts and referenced files; collect directory and memory samples separately.
5. Reuse cached measurements for immutable segment cores where valid. Recompute live-doc counts
   and any changed update-file metadata. Do not reuse a field ownership result across incompatible
   codec versions, segment identities, or generation revisions.
6. Materialize the immutable result, release reader/generation leases, and cache only result data
   for pagination. Do not keep segment files pinned for idle page tokens.

Initial proposed limits are a five-second cache for index summaries and a sixty-second cache for
field analysis; explicit refresh requests coalesce with existing work. Use two diagnostic workers,
at most one collection per generation, and a queue of sixteen jobs. Start with 100 fields per page,
a maximum of 500, and a 4 MiB response limit. Bound retained result memory and token lifetime;
return `RESOURCE_EXHAUSTED` with guidance to narrow path filters rather than truncate silently.
Tune these limits with the validation workload below.

Cancellation, deadlines, shutdown, and generation retirement cancel queued work and release
leases. A disappearing file during the non-atomic directory walk triggers a bounded retry or
an explicitly partial directory measurement. Unsupported field components affect coverage,
not the ability to return supported index statistics.

Use `INVALID_ARGUMENT` for invalid IDs, filters, sizes, or tokens; `NOT_FOUND` for unknown/dropped
indexes; `PERMISSION_DENIED` for missing role/grant; `FAILED_PRECONDITION` for stale generation
fences or expired snapshots; `UNAVAILABLE` for generations that are not ready; and
`RESOURCE_EXHAUSTED` for admission or result limits. Preserve `CANCELLED` and `DEADLINE_EXCEEDED`.

## Implementation plan

### Phase 1 Index summaries

Add typed per-index statistics and the generation lease, reader snapshot, file accounting, and
cache infrastructure. Cover logical counts, both storage sizes, both averages, timestamps, and
quality. Expose only memory measurements with a supported accounting source; return unavailable
for the rest. Add all four client methods and document their authorization and freshness.

### Phase 2 Field attribution

Add field statistics, immutable pagination, and component capability reporting. Start with vector
working-set APIs, verified codec ranges, and instrumented field-owned resources. Introduce small
codec adapters for the supported Lucene 9.11-compatible and 10.4 layouts rather than assuming the
latest codec handles every existing segment. Audit each quantized/native/flat reader wrapper.

Return all selected fields even when their measurements are unavailable. For broader postings,
doc-values, and point coverage, expose verified offsets/lengths from codec metadata where needed.
If a format cannot expose a safe breakdown, retain a shared/unattributed component. Evaluate
persisting an attribution sidecar on flush/merge only as a separate on-disk-format proposal;
it would not retroactively measure old segments and cannot remove cross-field compression costs.

### Phase 3 Diagnostic estimates and marginal savings

Consider an opt-in sampling tool for fields whose shared storage cannot be separated. Record the
sample population, sample size, selection method, and model; do not claim an error bound without
validating it. Keep uncompressed size estimates distinct from physical disk attribution.

For the question “how much space would removing this field save?”, use a controlled reindex
experiment: export/freeze the mapping, reingest a representative source dataset into otherwise
identical indexes with and without the selected field's indexing features, and compare sizes
under the same commit, merge, codec, and configuration conditions. Specify whether stored source
is also changed. This measures a dataset-specific marginal difference; individual differences
need not add up because compression and sharing change.

The server cannot automatically replay arbitrary source documents. Such an experiment requires
the caller's source data; stored source may be disabled or incomplete. Memory comparisons also
need a defined query workload and warmup, not just a comparison of cold index file sizes.

## Validation and release criteria

- Verify empty indexes, sparse documents, upsert replacement, deletion, unmerged deletes, and
  restart. Zero-document averages must remain absent even when index files occupy bytes.
- Check logical versus Lucene counts for embedded search fields and nested vectors, across
  partitions and all write concerns. Refresh must include completed writes without forcing commit.
- Validate mixed types, string multi-fields, document/embedded scopes, and configured but unused
  fields. Parent rollups must not double count child fields.
- Reconcile the storage ledger for compound/non-compound segments, update files, concurrent
  merges, retained readers, and both supported codec layouts. Cross-check exclusive ranges against
  synthetic segments with known layouts; unsupported ranges must remain visible in coverage.
- Test float/byte/bit, scalar/binary quantization, flat vectors and HNSW. Compare forwarded
  working-set categories with the underlying codec, including graph and quantized metadata.
- Test shared object identity, inclusive parent accounting, missing resource APIs, and empty
  vector accounting maps. Unknown must never turn into a false zero or apparent complete coverage.
- Verify index-scoped authorization, generation changes, drop/recreate races, deadlines,
  cancellation, token expiry, stable pagination, and diagnostic memory/queue limits.
- Benchmark large field cardinalities and many segments under active ingestion and query load.
  Record collection time, coordinator hold time, extra I/O, allocation, and impact on write/query
  latency before choosing final defaults. Basic summaries must not trigger field-data scans.
- Exercise both RPCs from independently installed Python, Rust, Java, and TypeScript clients.
  Examples and release packages must describe the same wire contract as the server.

The codec adapter spike is the main remaining sizing question: enumerate measured, shared, and
unsupported components for each supported codec before promising full per-field disk coverage.
The public contract should remain useful and truthful when that coverage is partial.

## Implementation notes for 0.5.0

Both RPCs and typed Python, Java, TypeScript, and Rust methods are implemented. The storage
ledger supports verified compound-entry offsets, per-field doc-values, points and norms metadata
for both supported layouts, plus exclusive format files. Postings, stored source, and other
multi-owner files remain shared where no verified per-field offsets exist. The response explains
coverage rather than allocating these bytes arbitrarily. Unsupported formats remain unattributed.

Reader and writer accounting and vector working-set forwarding are separate measurements.
Writer buffers, facet caches, and loaded Bloom data are included where their existing accounting
APIs permit; this does not provide full per-field heap or resident-memory accounting. Results
are cached; segment-level attribution memoization is a possible optimization after measurement.

Summary and field result caches last five and sixty seconds respectively. Immutable snapshots
and tokens expire after two minutes; conservative retained-result budgets are 16 MiB per snapshot
and 64 MiB in total, with at most 4096 tokens. Collection limits include 100,000 field metadata
entries and 200,000 physical files/ranges. Queue, cache, collection/barrier duration and supported
worker-thread allocation counters are exported through service metrics.

Phase 3 sampling and controlled reindex automation are optional diagnostics, not exposed by
these RPCs. A caller can use exported mappings and its own source data to run the controlled
comparison described above. No per-field measurement predicts universal removal savings.

## Initial load measurement

An observational Linux amd64 run used 1,000 dynamically indexed string fields, 500 seed documents,
and concurrent query/write loops, followed by 25 explicit summary and field refreshes over eight
seconds. Six segment metadata files remained at the end; merges were enabled. Reproduce with
`clients/tests/statistics_benchmark.py --launcher /path/to/mongot-standalone --output result.json`.

| Measurement | Observed result |
| --- | ---: |
| Field worker collection mean / max | 189 / 619 ms |
| Summary worker collection mean / max | 5.9 / 28.2 ms |
| Ingestion barrier mean / max (50 captures) | 0.34 / 5.33 ms |
| Field worker allocations per collection | 36.8 MB |
| Summary worker allocations per collection | 206 kB |
| Query p95, baseline / diagnostics | 28.8 / 34.9 ms |
| Write p95, baseline / diagnostics | 29.1 / 40.1 ms |
| Retained result estimate at end | 52.0 MB |

The process reported zero physical read bytes in both phases (warm filesystem cache). Read-call
bytes were 6.92 MB baseline and 4.22 MB during diagnostics; these include queries/ingestion and
cannot isolate diagnostic I/O. JVM thread allocation counters include transient collector/proto
objects, not retained heap. This single-host run is evidence of cost, not an overhead guarantee;
merge timing and warmup differ between sequential phases. Keep the bounded workers, queue and
cache defaults, and use cached results for dashboards rather than continuous explicit refresh.
