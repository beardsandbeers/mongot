# Push-mode architecture

Standalone mongot accepts BSON directly into selected indexes without pulling from MongoDB.
The unit of public identity, authorization, ingestion and deletion is an index.

```text
CreateIndex(mapping BSON) -> index definition -> existing mongot configuration/lifecycle
Upsert(index_id, BSON)    -> validate BSON -> prepareBatch -> IndexWriter.updateIndex
Delete(index_id, _id)     -> validate BSON -> IndexWriter.updateIndex(delete event)
Search(index_id, BSON)   -> existing query parser -> Lucene reader -> cursor manager
```

`LocalIndexCatalog` persists index definitions in an atomically replaced JSON file and holds the
data-directory lock. Its internal definitions retain mongot's required namespace/UUID fields;
these are implementation metadata derived from index identity, with no public collection resource.

`StandaloneRuntime` composes the existing Lucene factory, configuration manager, lifecycle,
initialized/live index catalogs and cursor manager. `ApiIngestionManager` implements the lifecycle's
replication interface as a push writer, with no MongoDB sync source. Lifecycle registration marks
an initialized index ready. API writes validate the batch and apply ordinary update/delete events
through the current writer's BSON mapping pipeline. Index retirement and mapping changes coordinate
with writes so a closed writer is never used.

Completion is expressed as applied, visible, or committed Lucene writes. Periodic commits and the
ordinary reader refresh operate independently. Graceful shutdown commits pending changes before
physical indexes close. There is no separate durable acceptance boundary, source document store,
mutation log, sequence watermark, snapshot builder, retention pin or persisted retry result.

Mapping updates create an empty generation and switch to it once initialized. The caller reingests
content. New indexes are empty regardless of content in other indexes. Drop removes index metadata
and schedules physical Lucene cleanup. Source ownership, replay, retry ordering and ingestion into
multiple indexes belong to the caller.

The dedicated bounded gRPC dispatcher, TLS/bearer interceptor and index grants remain. Query
execution uses the existing readers and cursors. Source fields are returned only when the index
schema explicitly configures Lucene stored source.

The former standalone source store and document-management workflows are intentionally removed.
The push-only protocol breaks compatibility with the collection-backed 0.2.x API; see the
[operating guide](standalone-mongot.md) for configuration and migration by reingestion.
