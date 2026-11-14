# mongot node push client

See [the client guide](https://github.com/beardsandbeers/nomongo-t/blob/main/clients/README.md) for build instructions, examples, TLS, deadlines and write
semantics. This client targets indexes directly using the schema bundled with this checkout.
Release 0.6.0 uses this push API and is incompatible with the older collection-backed 0.2.x API. Use the matching server.

Upsert replaces BSON documents by `_id`; Delete sends BSON `_id` wrappers. No source documents,
request ledger or collection catalog are maintained outside Lucene. Reingest after mapping changes.

Use `GetIndexMappings` to export configured fields and observed dynamic field types as a static
mapping, including mixed types per field. The response also includes a complete reusable index
definition with analyzers and other options preserved. See the client guide for an example.

Version 0.6.0 supports cross-index and self-join predicates in Search BSON. Use
`from: "indexName"`, explicit `localField`/`foreignField`, and a foreign `operator`
inside `compound.filter` or `compound.mustNot`. Both key fields must be strings
indexed as `token` with matching normalizers; arrays use any-shared-value semantics.
Native typed `_id` keys and foreign-document enrichment are outside this scope.
See the [join examples](https://github.com/beardsandbeers/nomongo-t/blob/standalone-v0.6.0/clients/README.md#join-searches-across-indexes).
