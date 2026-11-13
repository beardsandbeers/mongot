# Running standalone mongot

Standalone mongot runs the existing Lucene text/vector engine with a local authoritative
catalog, full BSON document store, mutation log, and retry ledger. It needs no MongoDB server,
connection URI, replica-set discovery, metadata polling, heartbeat, or initial sync. The ordinary
MongoDB-connected executable is unchanged. See the [design guide](standalone-mongot-grpc-guide.md)
for the durability and lifecycle contract.

## Docker quick start: Docker is the only prerequisite

From a checkout of this repository, with Docker Engine/Docker Desktop and the Compose plugin:

```sh
docker compose -f deploy/standalone/compose.yaml up --build --detach --wait
docker compose -f deploy/standalone/compose.yaml exec mongot mongotctl status
```

The first command builds from source inside Docker, packages the application with Java 21 and its
native libraries, starts it, and waits for an authenticated gRPC health check. No host JDK, Bazel,
compiler, MongoDB server, or Python installation is needed. The first build downloads the toolchains
and dependencies; subsequent builds reuse a BuildKit cache. Give Docker at least 6 GiB RAM and
15 GiB free disk for building. The supplied runtime limit is 4 GiB, with a 2 GiB Java heap.

The service is available at **localhost:27029 over TLS**. On first start, it generates a self-signed
certificate and a random admin token, then persists them alongside its data in the named volume
`mongot-standalone_state`. Credentials are generated at runtime and are never baked into an image.
The certificate covers `localhost`, `127.0.0.1`, and the Compose service name `mongot`; it lasts one
year. For a production hostname, install your own certificate and configure restricted principals
as described below. The default host port is published on loopback only.

The container runs as UID/GID **10001:10001**, with a read-only root filesystem in Compose. The
state volume is writable, and `/tmp` is an executable temporary filesystem because the native
crypto and SQLite libraries extract shared objects there. Docker sends SIGTERM and allows 90 seconds
for shutdown. The image contains the release JVM, application, licenses, API, operating guide, and
a small `mongotctl health|status` client; it needs no network downloads at runtime.

### Build an image without starting it

```sh
docker build -f deploy/standalone/Dockerfile -t mongot-standalone:local .
# Equivalent convenience target; requires Make as well as Docker:
make build.docker.standalone
```

To run that image using the Docker CLI without Compose:

```sh
docker run --detach --name mongot --restart unless-stopped --stop-timeout 90 \
  --memory 4g --read-only --tmpfs /tmp:rw,nosuid,exec,size=256m,mode=1777 \
  -p 127.0.0.1:27029:27029 -v mongot-standalone_state:/var/lib/mongot \
  mongot-standalone:local
docker exec mongot mongotctl status
```

Wait for startup before the status call. This uses the same state-volume name as Compose; stop the
Compose service before starting a CLI container on that volume. `docker stop --time 90 mongot`
stops it cleanly, and `docker rm mongot` removes the container while preserving the named volume.

The multi-stage Dockerfile keeps compilers and Bazel in the build stage. The final image uses
Ubuntu 24.04 and the bundled JVM. The build stage uses its native compiler for the image's own
architecture, avoiding the separate LLVM cross-compilation download. `BUILD_IMAGE` defaults to
Ubuntu 22.04; `RUNTIME_IMAGE` defaults to Ubuntu 24.04. `BUILD_JOBS` defaults to 2 and
can be changed with `docker build --build-arg BUILD_JOBS=4 ...` or `BUILD_JOBS=4 docker compose ...`.
Builds need access to Ubuntu package repositories and the repository's public dependency mirrors.
No registry push occurs. Tags can be changed with `-t`; set `MONGOT_IMAGE=your-tag` when using Compose.
For pinned deployments, record the image ID/digest and build from a fixed source revision rather
than relying on the mutable `:local` tag or base-image tags.

Linux amd64 is the verified build/runtime architecture. The Dockerfile also selects the repository's
Linux arm64 platform when built natively on arm64; that path needs its own validation. To build
amd64 on an ARM Docker Desktop host with emulation enabled, prefix the command with
`DOCKER_DEFAULT_PLATFORM=linux/amd64`. Build and run the same image architecture.

If a matching **Linux** release archive is already built, the fast path avoids rebuilding Java:

```sh
make build.docker.standalone.local
docker compose -f deploy/standalone/compose.yaml up --no-build --detach --wait
```

This runs the Bazel release target, stages the archive under the ignored
`deploy/standalone/.package/` directory, and selects `PACKAGE_SOURCE=local-package` in the same
Dockerfile. For an existing archive, copy it to
`deploy/standalone/.package/mongot-standalone.tgz` and run
`docker build --build-arg PACKAGE_SOURCE=local-package -f deploy/standalone/Dockerfile
-t mongot-standalone:local .`. The bundled JVM is executed during image construction, so a
macOS archive or a mismatched CPU architecture fails the image build.

### Daily container operations

Run these commands from the repository root:

```sh
docker compose -f deploy/standalone/compose.yaml ps
docker compose -f deploy/standalone/compose.yaml logs --tail=100 --follow
docker compose -f deploy/standalone/compose.yaml exec mongot mongotctl health
docker compose -f deploy/standalone/compose.yaml exec mongot mongotctl status
docker compose -f deploy/standalone/compose.yaml stop
docker compose -f deploy/standalone/compose.yaml start
```

Health checks management availability and authenticated TLS connectivity. Use `status`/GetIndex
to check individual index readiness and generation progress; a healthy process can still have a
building or failed index. Status prints protobuf text, including native engine metrics. Logs go to
stdout/stderr; Compose rotates them at 10 MiB with three files. `restart: unless-stopped` restarts
an exited process; Docker's unhealthy flag alone does not restart a running process.

`docker compose ... down` removes the containers/network and preserves the named volume. A later
`up` retains the instance ID, documents, mappings, retry history, certificate, and token. Adding
`--volumes` to `down` permanently removes this local state. Keep the Compose project name stable;
changing it selects a different named volume. Set `MONGOT_PORT=27030` to publish a different host
port. Other containers on the Compose network use `mongot:27029` with the same TLS trust/token.

To export client credentials without printing the token in logs:

```sh
mkdir -m 700 .standalone-client
docker compose -f deploy/standalone/compose.yaml cp mongot:/var/lib/mongot/config/server.crt .standalone-client/server.crt
docker compose -f deploy/standalone/compose.yaml cp mongot:/var/lib/mongot/config/admin.token .standalone-client/admin.token
chmod 600 .standalone-client/admin.token
```

Use the certificate as the client's trust anchor and the token as bearer metadata. The generated
stub example later in this guide can be run with
`--ca .standalone-client/server.crt --token-file .standalone-client/admin.token`. GetIndex/CreateIndex,
Write/WriteBatch, and Search/VectorSearch use the same API in containers and bare processes.

### Configuration and resource limits

The generated configuration lives at `/var/lib/mongot/config/standalone.yaml`; the authoritative
database, journal, and Lucene projections live under `/var/lib/mongot/data`. Edit a copy while
stopped, install it as the service user, then start the service:

```sh
docker compose -f deploy/standalone/compose.yaml cp mongot:/var/lib/mongot/config/standalone.yaml ./standalone.yaml
docker compose -f deploy/standalone/compose.yaml stop
# Edit standalone.yaml; keep storage.dataPath at /var/lib/mongot/data.
docker compose -f deploy/standalone/compose.yaml run --rm --no-deps -T --entrypoint sh mongot \
  -c 'set -eu; cd /var/lib/mongot/config; umask 077; cat > standalone.yaml.new; mv standalone.yaml.new standalone.yaml' \
  < standalone.yaml
docker compose -f deploy/standalone/compose.yaml start
```

Alternatively, mount an operator-managed config directory read-only at `/etc/mongot`, mount the
state volume at `/var/lib/mongot`, and pass `--config /etc/mongot/standalone.yaml` after the image
name. Explicit arguments bypass automatic credential/config generation. The supplied files must
be readable by UID 10001. Use absolute container paths, keep TLS enabled on `0.0.0.0`, and point
token/certificate paths at mounted files. When changing health credentials/address, set
`MONGOT_CONTROL_CA`, `MONGOT_CONTROL_TOKEN_FILE`, and `MONGOT_CONTROL_ADDRESS` accordingly. For an
explicitly configured plaintext loopback listener only, set `MONGOT_CONTROL_PLAINTEXT=true` and
an empty `MONGOT_CONTROL_TOKEN_FILE`; it will not be reachable through Docker port forwarding.

The service heap is controlled by `JAVA_TOOL_OPTIONS`, e.g. `-Xms128m -Xmx2g
-XX:MaxDirectMemorySize=512m`. Leave container memory for native allocations, mapped index pages,
thread stacks, and the OS cache. The `mongotctl` subprocess uses a separate 128 MiB maximum heap.
For bind-mounted storage, prepare its ownership for UID/GID 10001 before startup. Use a local
filesystem/volume with working file locks and durable fsync; one writer owns each state directory.

Certificate/token changes require a restart. Replacing a certificate requires distributing its new
trust chain to clients. Replace files deliberately while stopped; do not remove the whole config
directory to rotate credentials, as bootstrap would create a fresh admin identity. The entrypoint
preserves existing credentials and fails if it finds an incomplete config directory.

New bootstrap certificates are server-authentication leaf certificates (`CA:FALSE`). Older images
may have generated `CA:TRUE` certificates that strict clients such as Rustls reject with
`CaUsedAsEndEntity`. Existing volumes keep their certificates on upgrade; install a valid server
leaf certificate and redistribute its trust anchor using the rotation procedure above.

### Container backup, restore, and upgrade

A consistent v1 backup requires a stopped service. The following includes **both** data and TLS/auth
identity, so protect the resulting archive as a secret:

```sh
mkdir -p backups
umask 077
BACKUP="backups/standalone-$(date -u +%Y%m%dT%H%M%SZ).tgz"
docker compose -f deploy/standalone/compose.yaml stop
docker run --rm --user 10001:10001 --entrypoint tar \
  -v mongot-standalone_state:/state:ro mongot-standalone:local \
  -C /state -czf - . > "$BACKUP"
tar -tzf "$BACKUP" >/dev/null
docker compose -f deploy/standalone/compose.yaml start
```

To restore into a **fresh** volume and verify it on a different local port, set `BACKUP` to the
archive path first if you are using another shell:

```sh
RESTORE_VOLUME="mongot-standalone-restored-$(date -u +%Y%m%dT%H%M%SZ)"
docker volume create "$RESTORE_VOLUME"
docker run --rm -i --user 0:0 --entrypoint sh \
  -v "${RESTORE_VOLUME}:/var/lib/mongot" mongot-standalone:local \
  -c 'tar -xzf - -C /var/lib/mongot && chown -R 10001:10001 /var/lib/mongot' \
  < "$BACKUP"
docker run --detach --name mongot-restored --stop-timeout 90 \
  -p 127.0.0.1:27030:27029 -v "${RESTORE_VOLUME}:/var/lib/mongot" \
  mongot-standalone:local
docker exec mongot-restored mongotctl status
```

Allow initialization to finish before the last command. Check that the restored instance/index
IDs match, queries return the expected documents, and an unexpired retry returns its original
outcome. Stop the verification instance before using that state for production writes. Copying
only Lucene files or backing up a live volume with `tar` does not produce a supported backup.

For an upgrade: take a stopped backup, record the current image ID, build/tag the new source
revision, then run `docker compose ... up --no-build --detach --wait` with `MONGOT_IMAGE` set to
the new tag. The state volume is reused. Follow schema compatibility below; reverting an image
tag cannot undo a database migration. Restore the pre-upgrade backup into a fresh volume if the
older binary cannot read the upgraded schema. Images can be transferred with
`docker save mongot-standalone:local | gzip > mongot-image.tgz` and
`gzip -dc mongot-image.tgz | docker load`; transfer the separate state backup when moving data.

### Troubleshooting containers

| Symptom | Check/action |
| --- | --- |
| First build is slow | Dependencies/toolchains are downloading. Inspect the current build output; keep the BuildKit cache. Increase Docker disk/memory before increasing `BUILD_JOBS`. |
| `RESOURCE_EXHAUSTED` writes | Check GetStatus disk headroom/log backlog, in-flight bytes, batch limits, and failed generations. Preserve accepted source/log state. |
| Container exits with code 137 | Inspect Docker memory limits/OOM status; adjust container memory and heap together. |
| TLS hostname/trust failure | Trust the exported certificate, use a covered hostname, or supply your own certificate. Do not disable certificate verification. |
| `UNAUTHENTICATED` | Use the token from the current state volume; credentials persist across container recreation. |
| Unhealthy custom configuration | Set the `MONGOT_CONTROL_*` variables to match the listener, certificate, and health principal. Check `docker inspect` health output and service logs. |
| Permission denied/data directory in use | Check volume ownership (10001:10001) and ensure no second writer uses the same directory. |
| Port 27029 already allocated | Set `MONGOT_PORT` to an unused host port. |

## Build and start without Docker

Prebuilt Linux x86_64 server and client packages are available in the
[standalone-v0.1.0 prerelease](https://github.com/beardsandbeers/mongot/releases/tag/standalone-v0.1.0).
Follow its `INSTALL.md` and verify downloads against `SHA256SUMS`. The server archive contains its
own Java 21 runtime and native libraries; no host JVM or MongoDB server is required.

From the repository root:

```sh
bazel build //deploy:mongot-standalone
mkdir -p /tmp/standalone
tar -xzf bazel-bin/deploy/mongot-standalone.tgz -C /tmp/standalone
cd /tmp/standalone/mongot-standalone
./mongot-standalone --config standalone.yaml
```

The archive includes Java 21, native vector support, FIPS provider jars, the SQLite JDBC driver,
licenses, and `standalone.proto`. The launcher uses the bundled JVM and original signed provider
jars. `make build.deploy.standalone` selects the Linux x86_64 release platform explicitly.
For development, `bazel run //src/main/java/com/xgen/mongot/standalone:MongotStandalone --
--config /absolute/path/standalone.yaml` runs the same main class.

The sample config binds unauthenticated **loopback** `127.0.0.1:27029` and stores state in `./data`,
relative to the configuration file. `storage.dataPath` is required. Unknown/duplicate YAML keys,
non-standalone modes, MongoDB connection settings, and unsupported store/completion modes fail
startup. One process owns a data directory; a second process fails before opening its projections.
Send SIGTERM and wait for process exit before starting another process or copying its data.

## Secure an external listener

Create a random ASCII bearer token of at least 32 characters in a file readable only by the
service account. TLS keys and token files are local paths, resolved relative to the config file.
A non-loopback listener requires both TLS and at least one configured principal.

```yaml
mode: standalone
storage:
  dataPath: ./data
server:
  grpc:
    address: 0.0.0.0:27029
    tls:
      certificate: ./server-chain.pem
      privateKey: ./server-key.pem
    maxRpcDurationMillis: 60000
    principals:
      - name: administrator
        tokenFile: ./admin.token
        roles: [catalog, write, query]
        collections: ['*']
      - name: application
        tokenFile: ./application.token
        roles: [write, query]
        collections: ['REPLACE-WITH-COLLECTION-UUID']
```

Clients send `authorization: Bearer <token>` as gRPC metadata and verify the server certificate.
TLS server authentication and bearer credentials are supported; mutual TLS and online token
reload are not implemented. Restart to change principals. Roles authorize whole services;
collection grants further restrict resources and cursors. Collection creation and instance-wide
operations require the wildcard grant. Request IDs and cursors belong to the authenticated
principal. Renaming a principal changes its retry namespace. Health calls also require credentials
when authentication is configured. Do not log tokens, source BSON, or query payloads.

## API and data semantics

Generate ordinary clients from
[`standalone.proto`](../src/proto/standalone/v1/standalone.proto). This listener uses generated
protobuf messages, without MongoDB wire framing or MongoDB request headers. BSON fields carry
**complete binary BSON documents**, not JSON/Extended JSON text. JSON encodings of protobuf bytes
use base64. Resource IDs are local UUIDs for collections and ObjectId hex strings for indexes.

| Service | Methods | Required role |
| --- | --- | --- |
| CatalogService | Create/GetCollection, Create/Update/Drop/Get/ListIndexes | catalog |
| DocumentService | Write, WriteBatch, GetWriteResult, AwaitSequence | write |
| QueryService | Search, VectorSearch, GetMore, KillCursor | query |
| OperationsService | GetStatus | catalog plus `collections: ['*']` |

Create a collection, then create an index and poll GetIndex for `READY`. Names alone are not
resource identities. Index creation returns durable desired intent; physical initialization is
asynchronous. Lifecycle states are `PENDING`, `BUILDING`, `READY`, `FAILED`, and `DROPPED`.
GetIndex reports desired/active revisions and exact live/building generation watermarks.
An equivalent full definition under the same name resolves to the existing index; a conflicting
create returns ALREADY_EXISTS. UpdateIndex replaces the complete desired mapping, and Update/Drop
require the current `expected_revision` (first revision is 1). Stale revisions return ABORTED.
Retries return the original catalog result. ListIndexes uses best-effort keyset pagination.

Each mutation needs exactly one `_id`: string, ObjectId, BSON int32, or BSON int64. Identity uses
the existing Lucene ID encoding: BSON int32 `1` and int64 `1` are distinct identities. Preserve the
original BSON numeric width on replacement/deletion. Null/unsupported IDs, duplicate BSON keys,
malformed UTF-8, trailing bytes,
excessive nesting, and overlong encoded IDs are rejected before source acceptance.

- INSERT creates a source document only if that encoded ID is absent, regardless of mapping matches.
- UPSERT replaces the entire source document; omitted fields and old embedded-document blocks disappear.
- DELETE accepts exactly `{_id: value}` and is successful even if the document is already absent.
- A batch is ordered and non-atomic. Rejected items consume no sequence; valid later items can succeed.
  Each accepted item atomically persists its full source change, log position, and retry outcome.
- Write/WriteBatch normally remain pending until every accepted item has been applied by all captured
  live generations. Only `WRITE_ITEM_STATE_INDEXED` is successful mutation completion. An RPC's OK
  transport status alone does not mean every item succeeded; inspect all result states/statuses.
- Mapping mismatches follow the existing engine's field omission behavior. Unsupported definitions
  are rejected; engine apply failures stop that generation before it can advance past a failed event.
  Accepted source state remains authoritative, even on indexing failure.

Use a stable request ID and identical full request payload for retries. Reusing an ID with a
different operation/payload returns INVALID_ARGUMENT. A deadline, cancellation, transport error,
or indexing failure does not undo accepted work. Use GetWriteResult to distinguish rejected,
pending, indexed, and failed items, or retry the identical request. An interrupted batch can resume
its unaccepted tail. A permanently retired captured target causes the remaining tail to be rejected;
a prior accepted sequence is never silently reassigned to a replacement generation.

Retry recognition lasts for the configured `ingestion.retryRetentionHours` from original manifest
creation (24 hours by default). Expired requests may be removed, including interrupted manifests;
executing RPCs pin their manifests until they finish. After expiry, reusing an ID is a new request.
Client sequencing beyond this window needs a separate application protocol. Catalog tombstones
preserve dropped index identity; dropping an index does not delete source documents.

`INDEXED` is separate from commit and reader visibility. AwaitSequence requires the exact generation
ID and `APPLIED`, `COMMITTED`, or `VISIBLE` condition. Commit/refresh occur periodically in the same
ordered consumer lane. A visible reader can include writes not yet in a Lucene commit; SQLite FULL
WAL acceptance allows them to be replayed after a crash. Multi-partition recovery resumes from the
conservative checkpoint. Queries opened before a refresh retain their earlier reader snapshot.

Mapping updates and new historical indexes scan a consistent local source snapshot and then replay
the ordered suffix, including concurrent deletes. A durable pin protects that suffix. The old live
index serves queries during a staged rebuild. Cutover waits for a completed commit and refresh;
failed/incomplete builds do not replace it. Drop fences and drains that generation before its writer
closes and kills its cursors. A reused name gets a fresh index ID.

## Client libraries

[Java, Rust, Python and Node/TypeScript clients](../clients/README.md) expose the complete
standalone gRPC API with BSON helpers, TLS/authentication and per-call deadlines. The same directory
contains the cross-language end-to-end suite against the packaged standalone executable.

## Query boundary

Search accepts the existing `$search` expression body; VectorSearch accepts the existing
`$vectorSearch` expression body with an explicit client-provided `queryVector`. Specify `index_id`;
if the BSON includes `index`, it must match the resolved name. Optional `generation_id` rejects a
query if that generation is no longer live. Vector search supports the ordinary exact/approximate
engine paths and their validated definitions.

Each response contains `batch_bson` as `{results: [...]}` and the pinned generation. Native mongot
results contain IDs and scores, or configured `storedSource` when `returnStoredSource` is requested.
There is no MongoDB document hydration or general aggregation pipeline executor. Stored-source
include/exclude options follow each engine's existing validation (vector definitions do not accept
`storedSource: true`). Search metadata is returned separately as `metadata_bson`.

A nonzero cursor ID requires GetMore/KillCursor with the same principal and collection grant.
Cursors pin their initial reader and are killed on drop, exhausted automatically, or expired after
two idle minutes. The instance allows 1024 open cursors. Batches default to 100 documents, maximum
1000, with a 4 MiB soft engine limit and the configured transport response cap. Cancellation of a
new query discards an unclaimed cursor. Reissue a query after cursor expiry/drop.

MongoDB-backed synonym collections, views/materialized views, automatic embedding, and custom
index engines are rejected at definition acceptance. Custom analyzers, mappings, embedded documents,
stored source, sort, and vector fields use the existing engine validators. Feature flags use the
community defaults; no remote flag updater or MongoDB metadata task is started.

## Admission, status, and capacity

The sample/default limits are 64 in-flight RPCs, 256 MiB aggregate admitted request bytes, 16 MiB
per document, depth 100, 1000 documents/32 MiB per batch, 10 GiB retained logical log, and 256 MiB
free-disk headroom. Commit and refresh defaults are 1 second; the server enforces a 60-second RPC
ceiling even if clients omit deadlines. Capacity exhaustion returns RESOURCE_EXHAUSTED before new
source acceptance; a partially accepted batch retains its manifest and earlier item outcomes.
Use bounded client concurrency and retry backoff. The v1 catalog supports 64 collections and 64
active indexes; at most two generations perform snapshot/recovery builds concurrently.

OperationsService.GetStatus exposes instance readiness/schema, retry retention, full-source bytes,
SQLite/WAL size, free disk, retained log bytes, retry-manifest count, retention-pin count, active
index revisions/watermarks/snapshot progress/errors, and native Micrometer engine meters. Index
mappings are omitted from this instance-wide response; GetIndex returns the full desired definition.
`standalone.*` counters/timers cover mutation acceptance/rejection/bytes/retries, durable acceptance,
RPC duration/status/async waits/admission, in-flight permits/bytes/worker queue, replay, recovery,
apply, commit, refresh, and authentication failures. No request/document IDs appear in service metric
labels. Per-generation lag is collection accepted sequence minus the corresponding watermark.

Logical log pruning is transactional and bounded. Every running generation retains entries after
its durable commit; snapshot builds retain the suffix after their source snapshot. Failed consumers
keep their pins until drop or restart selects recovery, and backlog/free-disk limits stop admission.
After exclusive ownership is re-established on restart, stale process pins are removed. Each new
consumer registers a pin before reading its checkpoint; a checkpoint older than the durable pruning
floor requires a full source rebuild before readiness. SQLite reuses freed pages; pruning does not
necessarily shrink the database file. Avoid manual VACUUM against a live service. Online backups
and explicit collection deletion are outside this version's API.

The example below creates a **new persistent collection/index on every run**, sends bounded parallel
batches, waits for visibility, counts every search result, and prints measured throughput and batch
p50/p95/p99 latency. Use a disposable data directory for repeated capacity runs.

```sh
python3 -m venv /tmp/standalone-client
/tmp/standalone-client/bin/pip install grpcio grpcio-tools pymongo
mkdir -p /tmp/standalone-generated
/tmp/standalone-client/bin/python -m grpc_tools.protoc \
  -I src/proto/standalone/v1 --python_out=/tmp/standalone-generated \
  --grpc_python_out=/tmp/standalone-generated standalone.proto
PYTHONPATH=/tmp/standalone-generated /tmp/standalone-client/bin/python \
  scripts/standalone/client.py --documents 10000 --batch-size 100 --concurrency 4
```

For TLS add `--ca server-chain.pem --token-file admin.token`. Run single-document/batch sizes,
text/vector mappings, document sizes, and concurrency levels representative of your workload.
Report CPU, RAM, storage/filesystem, commit/refresh configuration, recovery duration, disk use, and
latency alongside throughput. These local measurements are capacity evidence, not a service SLA.

A packaged-launcher smoke benchmark on 2026-11-10 used an Intel N150 (4 cores), 10 GiB RAM,
Linux x86_64, a disk-backed ext4 data directory, SQLite WAL/FULL, 1-second commit/refresh,
one dynamic text index per collection, and 256-byte payloads plus `_id`/`title`. Each run verified
every document through Search/GetMore after visibility. Results from one short run per setting:

| Documents | Batch | Concurrency | Indexed docs/s | Batch p50 ms | Batch p95 ms |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 250 | 1 | 1 | 63.9 | 13.84 | 20.64 |
| 1000 | 100 | 4 | 199.5 | 1741.26 | 2944.11 |
| 1000 | 1000 | 1 | 154.8 | 6450.66 | 6450.66 |

The last setting contains one batch, so its percentile columns represent that single observation.
These runs establish a reproducible baseline; they do not measure sustained saturation, large
documents, vector capacity, or all host/storage failure modes. Keep both throughput and batch
latency when comparing settings. After the final run, the retained logical log had returned to
zero while all 2250 source documents remained searchable across the three collections.

## Backup, restore, and schema compatibility

The supported backup procedure requires a stopped process:

1. Stop admission with SIGTERM; wait for process exit and the data-directory lock to be released.
2. Copy **the entire data directory**, including `standalone.sqlite`, any `-wal`/`-shm` sidecars,
   `configJournal.json`, dynamic flags, all Lucene directories, and metadata. Keep ownership and
   permissions. Keep the configuration and secrets separately under the same protection as source data.
3. Restore to a fresh directory while stopped. Start with that directory's configuration. The instance,
   collection/index IDs, source sequences, desired mappings, and unexpired retry identities are retained.
4. Verify GetStatus, all index readiness, source/query results, and a known unexpired retry.

Lucene is a recoverable projection. To rebuild it, stop the process and make a complete backup first.
Retain all authoritative SQLite/root journal files and remove only the projection directories created
by the Lucene factory. Restart and wait for each index's snapshot/replay/commit/refresh readiness.
The process test exercises this restore path. Never remove the authoritative SQLite database to
repair a projection, and never copy just Lucene directories as a service backup. Restoring the same
backup into two writers creates divergent independent instances; this service provides no replication,
HA, multi-process writer coordination, or cross-host durability guarantee.

The store uses `application_id` plus `user_version` and checks integrity on open. Current schema 2
migrates schema 1 atomically, preserving instance/source/catalog/retry identities and initializing
retention accounting. Unknown/newer schemas and foreign/corrupt databases fail closed. Downgrading
to a binary that cannot read schema 2 is unsupported; preserve a stopped backup before upgrades.
Lucene checkpoint schema independently validates instance, collection, generation, definition hash,
build completion, and sequence. A corrupt/incompatible checkpoint fails the generation rather than
claiming progress. Incomplete source builds restart from a new consistent snapshot.

## Verification

```sh
bazel test //src/main/java/com/xgen/mongot/standalone/... \
  //src/test/unit/java/com/xgen/mongot/standalone/... --build_tests_only --test_output=errors
# Requires strace. Deny every outbound connect in the child service, including MongoDB discovery.
bazel test //src/test/unit/java/com/xgen/mongot/standalone:MongotStandaloneTest \
  --test_env=MONGOT_STANDALONE_NETWORK_GUARD=1 --test_output=errors
# Exercise an extracted release archive with the same independent-process acceptance test.
bazel test //src/test/unit/java/com/xgen/mongot/standalone:MongotStandaloneTest \
  --test_env=MONGOT_STANDALONE_NETWORK_GUARD=1 \
  --test_env=MONGOT_STANDALONE_LAUNCHER=/absolute/path/mongot-standalone/mongot-standalone
```

The suites exercise real SQLite, real text/vector Lucene readers, ordinary Netty generated clients,
strict source identity and retries, mixed batches, paused targets, cancellation/deadline/overload,
TLS and role authorization, concurrent mapping snapshots and deletes, multi-partition recovery,
real partial-partition commit I/O failure, source acceptance rollback, interrupted build restart,
unreadable desired-state fencing, retention pins/expiry/migration, process kill before projection commit,
and stopped restore with
projection deletion. The process test also exercises historical vector creation, mapping replacement,
source UPSERT/DELETE, durable index drop, and principal-scoped retry identity after recovery.
