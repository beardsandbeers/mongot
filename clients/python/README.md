# mongot python push client

See [the client guide](https://github.com/beardsandbeers/nomongo-t/blob/main/clients/README.md) for build instructions, examples, TLS, deadlines and write
semantics. This client targets indexes directly using the schema bundled with this checkout.
Release 0.3.0 uses this push API and is incompatible with the older collection-backed 0.2.x API. Use the matching server.

Upsert replaces BSON documents by `_id`; Delete sends BSON `_id` wrappers. No source documents,
request ledger or collection catalog are maintained outside Lucene. Reingest after mapping changes.
