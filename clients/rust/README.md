# Standalone mongot Rust client

The `mongot-standalone-client` crate exposes all standalone mongot gRPC operations in
`Client` and generated types in `api`. It includes BSON helpers, TLS with a supplied trust
certificate, bearer authentication, and fresh deadlines on every RPC. Tokio is required.

Install the downloaded `.crate` without a repository checkout:

```sh
tar -xzf mongot-standalone-client-0.1.0.crate
# Add to your application's Cargo.toml:
# mongot-standalone-client = { path = "../mongot-standalone-client-0.1.0" }
```

The schema and a vendored protoc build dependency are included. A current stable Rust toolchain
and access to crates.io are needed for dependency resolution. To run the JSON-lines CLI adapter:

```sh
cargo run --manifest-path mongot-standalone-client-0.1.0/Cargo.toml --locked
```

Set `MONGOT_ADDRESS`, `MONGOT_CA`, and `MONGOT_TOKEN_FILE` for the CLI, or construct
`ClientOptions` in an application. Omit CA/token only for a plaintext development listener.
Callers supply stable request IDs and inspect every write item's state/status. Wait for index
readiness and use AwaitSequence for visibility. No mutation is automatically retried.

Full API examples: https://github.com/beardsandbeers/mongot/blob/nomongo/clients/README.md
