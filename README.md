# NoMongo-T

Standalone [mongot](https://github.com/mongodb/mongot) with durable BSON document storage,
Lucene full-text and vector search, and a gRPC API. Run it without MongoDB and use the published
Python, Rust, Java, or Node/TypeScript clients to manage indexes and documents.

- **Docker image:** [`mybyte/nomongo-t`](https://hub.docker.com/r/mybyte/nomongo-t), tags `0.2.0` and `latest`.
- **Python client:** [`mongot-standalone-client`](https://pypi.org/project/mongot-standalone-client/).
- **Source and releases:** [beardsandbeers/nomongo-t](https://github.com/beardsandbeers/nomongo-t).

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

## Everyday operations

```bash
docker logs --tail 100 nomongo-t
docker exec nomongo-t mongotctl status
docker stop nomongo-t
docker start nomongo-t
```

Add `--follow` to `docker logs` to stream output; press Ctrl-C to stop following.

The named volume retains documents, indexes, retry history, and TLS/auth credentials when the
container is stopped or removed. Keep a stopped backup before upgrades; see the operating guide.

## Documentation

- [Detailed image instructions](deploy/standalone/DOCKERHUB.md): Compose, storage, credentials,
  configuration, resource limits, backup/restore, upgrades, and troubleshooting. These instructions
  are also published in the [Docker Hub overview](https://hub.docker.com/r/mybyte/nomongo-t).
- [Client API guide](clients/README.md): document commands, full-text/vector queries, BSON types,
  deadlines, visibility, and retry behavior for all four languages.
- [Standalone operating guide](docs/standalone-mongot.md): source builds, server configuration,
  durability, and index lifecycle.
- [Canonical gRPC schema](src/proto/standalone/v1/standalone.proto).
- [Building the upstream application](docs/building.md).

## Upstream and license

This repository builds on MongoDB Search's `mongot` engine. The original MongoDB-connected
community executable remains available in this source tree; standalone mode uses a separate
bootstrap and local store. This is an independent deployment of the engine, rather than the
MongoDB Community Search Docker image.

Source is available under the [Server Side Public License (SSPL) v1](LICENSE).
