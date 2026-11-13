# Standalone mongot Rust client

The `mongot-standalone-client` crate exposes all standalone mongot gRPC operations in
`Client` and generated types in `api`. It includes BSON helpers, TLS with a supplied trust
certificate, bearer authentication, and fresh deadlines on every RPC. Tokio is required.

Install from [crates.io](https://crates.io/crates/mongot-standalone-client):

```sh
cargo add mongot-standalone-client@0.2.0
```

Alternatively, install the downloaded `.crate` without a repository checkout:

```sh
tar -xzf mongot-standalone-client-0.2.0.crate
# Add to your application's Cargo.toml:
# mongot-standalone-client = { path = "../mongot-standalone-client-0.2.0" }
```

The schema and a vendored protoc build dependency are included. A current stable Rust toolchain
and access to crates.io are needed for dependency resolution. To run the JSON-lines CLI adapter:

```sh
cargo run --manifest-path mongot-standalone-client-0.2.0/Cargo.toml --locked
```

Set `MONGOT_ADDRESS`, `MONGOT_CA`, and `MONGOT_TOKEN_FILE` for the CLI, or construct
`ClientOptions` in an application. Omit CA/token only for a plaintext development listener.
The 0.2.0 server adds dedicated `upsert`, `delete`, `delete_by_query`, and
`get_delete_by_query_result` methods. Upsert takes complete BSON documents, Delete takes typed
`DocumentId` values, and successful commands wait for search visibility by default. Query deletion
runs on the server, preserves a durable target set for retries, and skips concurrently changed
documents. Inspect its deleted/conflict/missing/failed counts and `complete` flag.

Callers supply stable request IDs. Repeat identical requests to resume interrupted commands;
no mutation is automatically retried. Wait for index readiness before writing. Legacy
Write/WriteBatch callers must inspect every item's state/status and use AwaitSequence for visibility.

Full API examples: https://github.com/beardsandbeers/mongot/blob/nomongo/clients/README.md
