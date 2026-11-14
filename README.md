# NoMongo-T

NoMongo-T is a standalone full-text and vector search server built from **mongot** and
**Apache Lucene**. Your application creates indexes, pushes BSON documents into them, and
runs searches over gRPC. A MongoDB server is not required.

Use it for keyword search with mongot's mappings and analyzers, vector similarity search with
your own embeddings, or both. Indexes persist on disk. Clients are available for
[Python, Rust, Java, and TypeScript](clients/README.md).

## Quick start

You need Docker. The image includes Java 21 and the native libraries. The published `0.3.0`
image targets Linux amd64. ARM hosts need amd64 emulation and the `--platform linux/amd64`
option.

```sh
docker run --detach --name nomongo-t \
  --publish 127.0.0.1:27029:27029 \
  --memory 4g \
  --volume nomongo-t-data:/var/lib/mongot \
  mybyte/nomongo-t:0.3.0
```

The API listens at **localhost:27029 over gRPC with TLS**. On first startup, the container
generates a certificate and an admin bearer token. Both credentials and index data persist in
the named volume.

Check readiness; retry after a few seconds if startup is still in progress:

```sh
docker exec nomongo-t mongotctl health
```

A ready server prints `SERVING`. To inspect its indexes or startup logs:

```sh
docker exec nomongo-t mongotctl status
docker logs --tail 50 nomongo-t
```

### Connect from Python

Copy the generated credentials to your machine and install the matching client:

```sh
mkdir -p .nomongo-t
docker cp nomongo-t:/var/lib/mongot/config/server.crt .nomongo-t/server.crt
docker cp nomongo-t:/var/lib/mongot/config/admin.token .nomongo-t/admin.token
chmod 600 .nomongo-t/admin.token

python3 -m venv .venv-nomongo-t
.venv-nomongo-t/bin/python -m pip install mongot-standalone-client==0.3.0
```

Save the following as `demo.py`, then run `.venv-nomongo-t/bin/python demo.py`. It creates a
text index, indexes two documents, searches for coffee, and demonstrates replacement and deletion.

```python
import time

from mongot_standalone import Client, api, decode_bson, encode_bson


def wait_ready(client, index_id):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        index = client.get_index(api.GetIndexRequest(index_id=index_id))
        if index.lifecycle_state == "READY":
            return
        if index.lifecycle_state == "FAILED":
            raise RuntimeError(index.failure_message or "Index initialization failed")
        time.sleep(0.1)
    raise TimeoutError("Index did not become ready")


with Client(
    "localhost:27029",
    ca_file=".nomongo-t/server.crt",
    token_file=".nomongo-t/admin.token",
) as client:
    index = client.create_index(api.CreateIndexRequest(
        name="articles",
        type=api.INDEX_TYPE_SEARCH,
        definition_bson=encode_bson({
            "mappings": {"dynamic": True},
            "storedSource": True,
        }),
    ))
    wait_ready(client, index.index_id)

    client.upsert_many(index.index_id, [
        {"_id": "coffee", "title": "Coffee brewing guide"},
        {"_id": "tea", "title": "Tea brewing guide"},
    ])

    response = client.search(api.QueryRequest(
        index_id=index.index_id,
        query_bson=encode_bson({
            "text": {"path": "title", "query": "coffee"},
            "returnStoredSource": True,
        }),
    ))
    hits = decode_bson(response.batch_bson)["results"]
    print([hit["storedSource"] for hit in hits])
    # [{'_id': 'coffee', 'title': 'Coffee brewing guide'}]

    client.upsert(index.index_id, {"_id": "coffee", "title": "Espresso guide"})
    client.delete(index.index_id, "tea")
```

`storedSource: True` lets the query return document fields from Lucene. Each document needs an
`_id`. Upsert replaces the indexed document with the supplied fields, so include all the fields
you want to retain. Creating the same index name with an equivalent definition reuses its ID,
so you can rerun this example.

### Vector search

Append this to `demo.py` to try vector search. It reuses `wait_ready` from the text example.
The small vectors are illustrative; use embeddings from your model and set `numDimensions`
to their dimension count.

```python
with Client(
    "localhost:27029",
    ca_file=".nomongo-t/server.crt",
    token_file=".nomongo-t/admin.token",
) as client:
    index = client.create_index(api.CreateIndexRequest(
        name="article-vectors",
        type=api.INDEX_TYPE_VECTOR_SEARCH,
        definition_bson=encode_bson({
            "fields": [{
                "type": "vector",
                "path": "embedding",
                "numDimensions": 3,
                "similarity": "cosine",
            }],
        }),
    ))
    wait_ready(client, index.index_id)

    client.upsert_many(index.index_id, [
        {"_id": "coffee", "embedding": [1.0, 0.0, 0.0]},
        {"_id": "tea", "embedding": [0.0, 1.0, 0.0]},
    ])

    response = client.vector_search(api.QueryRequest(
        index_id=index.index_id,
        query_bson=encode_bson({
            "path": "embedding",
            "queryVector": [1.0, 0.0, 0.0],
            "exact": True,
            "limit": 2,
        }),
    ))
    print(decode_bson(response.batch_bson)["results"])
    # IDs and similarity scores, with "coffee" ranked first.
```

Each index receives its own writes. To search the same content with text and vector indexes,
submit the corresponding documents to each index. For larger result sets, use `GetMore` when
the query returns a nonzero cursor ID; see the [client guide](clients/README.md).

## Configure the container

Docker options control resource limits and networking. `JAVA_TOOL_OPTIONS` configures the JVM.
A YAML file configures the server's listener, authentication, indexing limits, and timing.

The examples below are alternative launch configurations. To change options on the quick-start
container, stop and remove the container, then recreate it with the same data volume:

```sh
docker stop --time 90 nomongo-t
docker rm nomongo-t
```

The `nomongo-t-data` volume keeps the indexes and generated credentials across container recreation.

### Give Java more heap

The image defaults to `-Xms128m -Xmx2g -XX:MaxDirectMemorySize=512m`. For an **8 GiB container
limit with a 4 GiB maximum Java heap**:

```sh
docker run --detach --name nomongo-t \
  --publish 127.0.0.1:27029:27029 \
  --memory 8g \
  --env JAVA_TOOL_OPTIONS="-Xms512m -Xmx4g -XX:MaxDirectMemorySize=512m" \
  --volume nomongo-t-data:/var/lib/mongot \
  mybyte/nomongo-t:0.3.0
```

`--memory` limits the entire container; `-Xmx` limits only the Java heap. Leave space outside the
heap for direct buffers, native code, thread stacks, and Lucene's memory-mapped files and page
cache. Setting `JAVA_TOOL_OPTIONS` replaces the image's complete default value.

The bundled `mongotctl` uses its own small heap, so health checks do not inherit the service's
larger heap setting.

### Use your own certificates, keys, and token

Prepare a directory containing your server certificate chain, its matching **unencrypted
PKCS#8 PEM private key**, the CA certificate trusted by clients, and a configuration file:

```text
nomongo-config/
  standalone.yaml
  server.crt       # server certificate, followed by any intermediate certificates
  server.key       # private key matching server.crt
  ca.crt           # trusted CA certificate or CA bundle
  admin.token
```

Generate an admin token:

```sh
mkdir -p nomongo-config
openssl rand -hex 32 > nomongo-config/admin.token
```

Put this in `nomongo-config/standalone.yaml`. Relative certificate and token paths resolve
against this file's directory.

```yaml
mode: standalone
storage:
  dataPath: /var/lib/mongot/data
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
        indexes: ['*']
```

The container runs as UID/GID **10001:10001**. Mounted directories must be traversable and files
readable by that identity. For files you own on a Linux host, this grants the container's group
read access while keeping the files accessible to your host user:

```sh
sudo chgrp -R 10001 nomongo-config
chmod 750 nomongo-config
chmod 640 nomongo-config/*
```

Launch with the directory mounted read-only. This example expects the certificate to cover
`localhost`, which the in-container health check uses:

```sh
docker run --detach --name nomongo-t \
  --publish 127.0.0.1:27029:27029 \
  --memory 4g \
  --volume nomongo-t-data:/var/lib/mongot \
  --volume "$(pwd)/nomongo-config:/etc/nomongo-t:ro" \
  --env MONGOT_CONTROL_ADDRESS=localhost:27029 \
  --env MONGOT_CONTROL_CA=/etc/nomongo-t/ca.crt \
  --env MONGOT_CONTROL_TOKEN_FILE=/etc/nomongo-t/admin.token \
  mybyte/nomongo-t:0.3.0 --config /etc/nomongo-t/standalone.yaml
```

`--config` selects your YAML file instead of automatic credential generation. The
`MONGOT_CONTROL_*` variables configure `mongotctl` and Docker's health check; the server itself
reads its settings from YAML.

For a certificate issued only to a service hostname such as `search.example.com`, replace the
control address above and add a container-local hostname mapping:

```sh
  --env MONGOT_CONTROL_ADDRESS=search.example.com:27029 \
  --add-host search.example.com:127.0.0.1 \
```

Clients must also connect using a hostname covered by the certificate. For example:

```python
from mongot_standalone import Client, api

with Client(
    "search.example.com:27029",
    ca_file="nomongo-config/ca.crt",
    token_file="nomongo-config/admin.token",
) as client:
    print(client.get_status(api.GetStatusRequest()))
```

Configure DNS and the published host interface for access from other machines. The quick-start
`--publish 127.0.0.1:27029:27029` exposes the service only on the Docker host.

### Other useful settings

| Setting | Example | Effect |
| --- | --- | --- |
| Host port | `--publish 127.0.0.1:28029:27029` | Connect to port 28029 on the host; the server still listens on 27029 inside Docker. |
| CPU limit | `--cpus 4` | Limit the container to four CPUs. |
| Restart policy | `--restart unless-stopped` | Restart the service after a crash or Docker restart. |
| Host data directory | `--volume "$(pwd)/data:/var/lib/mongot"` | Store persistent state in a host directory; give UID/GID 10001 write access. |
| Java heap | `--env JAVA_TOOL_OPTIONS="-Xms512m -Xmx4g -XX:MaxDirectMemorySize=512m"` | Override JVM memory settings; also size the container memory limit. |

For a custom YAML configuration, indexing limits and timing can be adjusted with:

```yaml
ingestion:
  maxInFlightRequests: 64
  maxBatchDocuments: 1000
  maxBatchBytes: 33554432
  maxQueuedBytes: 268435456
  commitIntervalMillis: 1000
querying:
  refreshIntervalMillis: 1000
```

See the [operating guide](docs/standalone-mongot.md) for all settings and index-level permissions.

## Write behavior and persistence

- Writes default to `concern="visible"`: a fresh search can observe the change when the call
  returns. Use `concern="committed"` to wait for a durable Lucene commit; reader refresh happens
  separately. Background commits run at `commitIntervalMillis`.
- Batches are ordered and can partially apply on failure or disconnect. Keep retries ordered
  against newer writes. Bound client concurrency and back off on `RESOURCE_EXHAUSTED`.
- Updating an index mapping creates an empty index generation. Reingest your content after the
  new revision becomes ready.
- Run one server per data volume. To back up, stop the container cleanly and copy the entire
  volume. Keep the volume when upgrading or recreating the container.

## Build from source

```sh
git clone https://github.com/beardsandbeers/nomongo-t.git
cd nomongo-t
docker compose -f deploy/standalone/compose.yaml up --build --wait
```

This builds the engine and native libraries inside Docker, then starts the service with TLS,
persistent storage, and a 4 GiB container memory limit. Host Java and Bazel installations are
not needed. For client development, install `./clients/python` instead of the PyPI package.

## More documentation

- [Client API and language bindings](clients/README.md)
- [Server configuration, write semantics, and backpressure](docs/standalone-mongot.md)
- [Architecture](docs/standalone-mongot-grpc-guide.md)
- [Release downloads](https://github.com/beardsandbeers/nomongo-t/releases/tag/standalone-v0.3.0)
