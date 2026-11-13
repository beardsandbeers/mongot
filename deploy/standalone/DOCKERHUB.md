# NoMongo-T: standalone mongot

`mybyte/nomongo-t` runs mongot's Lucene full-text/vector engine with a durable local BSON store
and a gRPC API. It does not require a MongoDB server. The image includes Java 21, native vector
libraries, SQLite, TLS bootstrap, and an authenticated health/status client.

[Source](https://github.com/beardsandbeers/nomongo-t) ·
[Client API](https://github.com/beardsandbeers/nomongo-t/blob/main/clients/README.md) ·
[Operating guide](https://github.com/beardsandbeers/nomongo-t/blob/main/docs/standalone-mongot.md) ·
[Python package](https://pypi.org/project/mongot-standalone-client/)

## Images and requirements

| Tag | Purpose | Platform |
| --- | --- | --- |
| `mybyte/nomongo-t:0.2.0` | Versioned 0.2.0 server and API | Linux amd64 |
| `mybyte/nomongo-t:latest` | Current published standalone release | Linux amd64 |

The repository is public; pulling does not require Docker Hub login. Prefer a versioned tag or
digest for deployments. The JVM heap has a 2 GiB maximum; allow headroom for native allocations,
mapped index pages, and cache when setting your own resource limits. Python is needed only for
the client example, not inside the server container. There is no separate database service to start.

## Quickstart: Docker + Python

You need Docker and Python 3.10+ with `venv`/pip. No MongoDB server, host Java, Bazel, or
repository checkout is needed. The image is **Linux amd64**. On ARM hosts with amd64 emulation,
add `--platform linux/amd64` to the `docker run` command.

### 1. Start the server

```bash
docker run -d --name nomongo-t \
  -p 127.0.0.1:27029:27029 \
  -v nomongo-t-data:/var/lib/mongot \
  mybyte/nomongo-t:0.2.0

# Wait for the authenticated health check to report SERVING.
for attempt in {1..60}; do
  if docker exec nomongo-t mongotctl health >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec nomongo-t mongotctl health
```

The listener is **gRPC over TLS on localhost:27029**. On first start, the container generates a
certificate and admin token and saves them with its data in `nomongo-t-data`. The token stays
stable across restarts. Port 27029 is a gRPC endpoint for the SDKs, rather than an HTTP UI or
MongoDB wire-protocol listener.

### 2. Export client credentials and install the Python client

On Debian/Ubuntu, install `python3-venv` first if creating a virtual environment reports a missing
`ensurepip` module. Other Python distributions normally include it.

```bash
mkdir -p .nomongo-t
chmod 700 .nomongo-t
docker cp nomongo-t:/var/lib/mongot/config/server.crt .nomongo-t/server.crt
docker cp nomongo-t:/var/lib/mongot/config/admin.token .nomongo-t/admin.token
chmod 600 .nomongo-t/admin.token

python3 -m venv .venv-nomongo-t
. .venv-nomongo-t/bin/activate
python -m pip install 'mongot-standalone-client==0.2.0'
```

Keep the token private. Run the Python example from the same working directory so the relative
credential paths resolve correctly. The SDK validates TLS using the exported certificate.

### 3. Create an index, upsert documents, search, and delete

```bash
python - <<'PY'
import time
from mongot_standalone import Client, api, encode_bson, decode_bson

with Client(
    "localhost:27029",
    ca_file=".nomongo-t/server.crt",
    token_file=".nomongo-t/admin.token",
) as client:
    collection = client.create_collection(api.CreateCollectionRequest(
        request_id="quickstart-collection-v1", database="demo", name="articles",
    ))
    index = client.create_index(api.CreateIndexRequest(
        request_id="quickstart-index-v1", collection_id=collection.collection_id,
        name="text", type=api.INDEX_TYPE_SEARCH,
        definition_bson=encode_bson({"mappings": {"dynamic": True}}),
    ))

    deadline = time.monotonic() + 60
    while index.lifecycle_state != "READY":
        if index.failure_message:
            raise RuntimeError(index.failure_message)
        if time.monotonic() >= deadline:
            raise TimeoutError("Index did not become ready")
        time.sleep(0.2)
        index = client.get_index(api.GetIndexRequest(index_id=index.index_id))

    docs = client.collection(collection.collection_id)
    docs.upsert_many([
        {"_id": "coffee-1", "title": "Coffee brewing basics"},
        {"_id": "coffee-2", "title": "An introduction to coffee"},
        {"_id": "tea-1", "title": "A guide to green tea"},
    ])

    query = {"text": {"path": "title", "query": "coffee"}}
    response = client.search(api.QueryRequest(
        index_id=index.index_id, query_bson=encode_bson(query), batch_size=10,
    ))
    hits = []
    while True:
        hits.extend(decode_bson(response.batch_bson)["results"])
        if not response.cursor_id:
            break
        response = client.get_more(api.GetMoreRequest(cursor_id=response.cursor_id))

    print("Coffee matches:", len(hits))
    print("Deleted by ID:", docs.delete("coffee-1").deleted_count)
    print("Deleted by query:", docs.delete_by_query(index.index_id, query).deleted_count)
    print("Collection:", collection.collection_id)
    print("Index:", index.index_id)
PY
```

Expected counts are `Coffee matches: 2`, `Deleted by ID: 1`, and `Deleted by query: 1`.
The tea document remains. You can rerun the example: it reuses the collection/index and seeds the
three documents again. Writes wait for search visibility by default.

`upsert` replaces a complete document; `delete` takes its ID directly. IDs must be strings,
ObjectIds, or signed BSON int32/int64 integers. UUIDs can be represented as strings. Query deletion
uses the server's search expression syntax, saves progress for retries, and skips concurrently
changed documents. It is an ordered, non-atomic operation.

## Use Docker Compose without a checkout

This is an alternative to the Docker CLI example. Stop the earlier container before reusing
port 27029. Save this as `compose.yaml` in an empty working directory:

```yaml
name: nomongo-t
services:
  nomongo-t:
    image: mybyte/nomongo-t:0.2.0
    ports:
      - "127.0.0.1:27029:27029"
    volumes:
      - state:/var/lib/mongot
volumes:
  state:
```

```bash
docker compose up --wait
docker compose exec nomongo-t mongotctl status
mkdir -p .nomongo-t
chmod 700 .nomongo-t
docker compose cp nomongo-t:/var/lib/mongot/config/server.crt .nomongo-t/server.crt
docker compose cp nomongo-t:/var/lib/mongot/config/admin.token .nomongo-t/admin.token
chmod 600 .nomongo-t/admin.token
```

The Docker CLI and Compose examples use different volume names: Compose
creates `nomongo-t_state`; the CLI example uses `nomongo-t-data`. Use the same project name and
volume across recreations to keep your data. `docker compose down` preserves the volume;
`docker compose down --volumes` deletes it. On ARM hosts using amd64 emulation, add
`platform: linux/amd64` to the service. For clients in other containers, use a certificate
covering the service hostname or configure the network alias `mongot`, which the generated
certificate already covers.

## Storage and credentials

The image runs as **UID/GID 10001:10001**. Mount a persistent volume at `/var/lib/mongot`:

| Path | Contents |
| --- | --- |
| `/var/lib/mongot/config/standalone.yaml` | Generated server configuration |
| `/var/lib/mongot/config/server.crt` | TLS server certificate, also used as the client trust anchor |
| `/var/lib/mongot/config/server.key` | Private TLS key; keep it inside protected server storage |
| `/var/lib/mongot/config/admin.token` | Random admin bearer token with catalog/write/query roles |
| `/var/lib/mongot/data` | SQLite source/catalog/retry ledger, journal, and Lucene indexes |

Bootstrap happens only when no configuration exists. It creates a server certificate valid for
one year with SANs `localhost`, `127.0.0.1`, and `mongot`, plus a random 64-character token.
Existing volumes retain their configuration, certificate, token, and instance ID on restart.
An incomplete existing config directory is rejected rather than replaced with a new identity.

The state volume and `/tmp` must be writable; Docker's defaults already provide a writable
`/tmp`. It must also allow execution because native crypto/SQLite libraries extract shared
objects there. Named volumes initialize with the image's ownership. For host bind mounts,
prepare the state directory so UID/GID 10001 can read and write it. Use storage with reliable
file locks and fsync. One running process may own a data directory.

For use on another hostname, install a certificate with that hostname in its SANs and update
clients to trust its issuing chain. The generated certificate's private key and bearer token
are credentials, so protect the state volume and backups. Never bake them into a derived image.

## Operator-supplied configuration

Passing application arguments bypasses automatic credential/config generation. Put
`standalone.yaml`, `server.crt`, `server.key`, and `admin.token` in a host `config` directory.
Use a token of at least 32 ASCII characters and make the files readable by UID 10001. For the
health command below, the certificate must include `localhost` as well as your client hostname.

Example `config/standalone.yaml`:

```yaml
mode: standalone
storage:
  dataPath: /var/lib/mongot/data
  sourceStore:
    engine: sqlite
    synchronous: full
server:
  grpc:
    address: 0.0.0.0:27029
    tls:
      certificate: server.crt
      privateKey: server.key
    principals:
      - name: admin
        tokenFile: admin.token
        roles: [catalog, write, query]
        collections: ['*']
```

Start a separate container with that configuration:

```bash
docker run -d --name nomongo-t-custom \
  -p 127.0.0.1:27029:27029 \
  -v nomongo-t-data:/var/lib/mongot \
  -v "$PWD/config:/etc/nomongo-t:ro" \
  -e MONGOT_CONTROL_ADDRESS=localhost:27029 \
  -e MONGOT_CONTROL_CA=/etc/nomongo-t/server.crt \
  -e MONGOT_CONTROL_TOKEN_FILE=/etc/nomongo-t/admin.token \
  mybyte/nomongo-t:0.2.0 --config /etc/nomongo-t/standalone.yaml
```

Stop the earlier container before reusing its port or data volume. The `MONGOT_CONTROL_*`
variables configure the bundled health/status client, while the service itself reads YAML.
Certificate and token paths in YAML are relative to that YAML file. For external access, use
your own certificate and collection/role-scoped principals, choose the required host port
binding, and restrict network access appropriately. See the operating guide for all limits.

Set a different heap when starting the container if needed, for example:

```text
-e JAVA_TOOL_OPTIONS="-Xms128m -Xmx2g -XX:MaxDirectMemorySize=512m"
```

Leave memory outside the heap for native allocations and cache. The bundled `mongotctl` uses
its own small heap. No service initialization or downloads require network access at runtime.

## Health, logs, stop, and restart

```bash
docker inspect --format '{{.State.Health.Status}}' nomongo-t
docker exec nomongo-t mongotctl health
docker exec nomongo-t mongotctl status
docker logs --tail 100 nomongo-t
docker stop nomongo-t
docker start nomongo-t
```

Add `--follow` to `docker logs` to stream output; press Ctrl-C to stop following.

`health` checks authenticated TLS connectivity and management availability. `status` reports
instance identity, store schema, source/log/disk metrics, indexes, and generation progress.
A healthy container can still have a building or failed index; inspect its index state before
writing or querying it. Docker health checks do not automatically restart unhealthy containers.
Stop/recreate/start does not delete the volume.

The service handles SIGTERM and uses Docker's default shutdown timeout. Keep log retention
bounded in your Docker daemon or Compose configuration, for example `json-file` with
`max-size: 10m` and `max-file: '3'`.

## Backup and restore

Stop the server before copying state. Back up the **entire volume**, including credentials,
SQLite files/sidecars, journals, and Lucene directories. The archive contains secrets.

For the Docker CLI volume from the quickstart:

```bash
mkdir -p backups
chmod 700 backups
umask 077
docker stop nomongo-t
docker run --rm --entrypoint tar \
  -v nomongo-t-data:/state:ro mybyte/nomongo-t:0.2.0 \
  -czf - -C /state . > backups/nomongo-t-state.tgz
tar -tzf backups/nomongo-t-state.tgz >/dev/null
docker start nomongo-t
```

Restore into a fresh volume:

```bash
docker volume create nomongo-t-restored
docker run --rm -i --user 0:0 --entrypoint sh \
  -v nomongo-t-restored:/var/lib/mongot mybyte/nomongo-t:0.2.0 \
  -c 'tar -xzf - -C /var/lib/mongot && chown -R 10001:10001 /var/lib/mongot' \
  < backups/nomongo-t-state.tgz
docker run -d --name nomongo-t-restored \
  -p 127.0.0.1:27030:27029 \
  -v nomongo-t-restored:/var/lib/mongot mybyte/nomongo-t:0.2.0
```

Wait for readiness and verify `mongotctl status`, collection/index IDs, and searches before using
the restored service. The backup retains the original TLS credentials; export them from the
restored container if needed. Port 27030 is used here to avoid the original server's port.
For Compose, substitute its `nomongo-t_state` volume and Compose stop/start commands.

## Upgrade and remove

Take a stopped backup before upgrading. Pull the desired version, stop/remove the old container,
and recreate it with the **same volume** and the new image tag. Removing a container preserves
its named volume; deleting the volume permanently removes documents and credentials.

The 0.2.0 server uses store schema 3 and migrates schemas 1/2. Older 0.1.0 servers cannot read an
upgraded schema 3 volume. Rolling back the image alone cannot reverse a migration; restore the
pre-upgrade backup to a fresh volume for rollback. `latest` is a moving tag, so pin a version or
digest when you need repeatable deployments.

To remove the quickstart container while keeping its state:

```bash
docker stop nomongo-t
docker rm nomongo-t
```

To deliberately delete all quickstart data and credentials after removing the container:

```bash
docker volume rm nomongo-t-data
```

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Image architecture mismatch | This image is Linux amd64; use the platform flag and a host with amd64 support/emulation. |
| Container exits or is unhealthy | Read `docker logs nomongo-t` and health-check output. Check storage permissions, complete configuration, and available memory/disk. |
| Port 27029 already in use | Choose another host port, e.g. `-p 127.0.0.1:27030:27029`, and use that address in the Python client. |
| TLS validation failure | Use the certificate from this volume and a hostname covered by its SANs. Custom certificates must also match the health client's address. |
| `UNAUTHENTICATED` | Use `admin.token` from this volume; another container/volume has different credentials. |
| `UNAVAILABLE` for queries/writes | Wait for the relevant index's `READY` state; inspect its failure message. Health alone does not mean every index is ready. |
| `RESOURCE_EXHAUSTED` | Inspect disk headroom, source-log backlog, configured batch limits, and request/cursor capacity. |
| Exit code 137 | Inspect the container's OOM status and memory limits; keep headroom beyond the Java heap. |
| Permission denied/data directory locked | Ensure UID 10001 owns writable state and only one server uses that directory. |

Only strings, ObjectIds, and signed int32/int64 values are valid document IDs in 0.2.0. Upserts
replace full documents. Delete-by-query accepts a search expression, not a MongoDB filter, and
requires both query and write roles. On an interrupted command, preserve its request ID and
identical payload for a retry; inspect durable progress before continuing large operations.

## Project and license

Source: [github.com/beardsandbeers/nomongo-t](https://github.com/beardsandbeers/nomongo-t).
The source derives from MongoDB's mongot engine and is distributed under the
[Server Side Public License v1](https://github.com/beardsandbeers/nomongo-t/blob/main/LICENSE).
This independent standalone deployment uses a separate local bootstrap/store. MongoDB-connected
community mode remains a separate executable in the source tree.
