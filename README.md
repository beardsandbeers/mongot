# NoMongo-T: mongot push mode

Run mongot's existing Lucene text and vector search engine without a MongoDB server.
Create an index with the ordinary mongot mapping schema, push BSON documents to that index,
and query it over gRPC.

Content is stored only in Lucene. There is no collection API, source document database,
mutation log, or durable retry ledger. Each index receives its own writes. Creating a new index
or updating a mapping requires the caller to reingest content from its own source.

## Docker and Python quickstart

Release **0.3.0** is published as [mybyte/nomongo-t](https://hub.docker.com/r/mybyte/nomongo-t).
Run the image with a fresh persistent volume:

```sh
docker run -d --name mongot -p 127.0.0.1:27029:27029 \
  -v nomongo-t-push_state:/var/lib/mongot mybyte/nomongo-t:0.3.0
docker exec mongot mongotctl status
```

Wait for startup before calling status. The image includes Java and native libraries. First
startup writes the TLS certificate and admin token to `/var/lib/mongot/config` in the volume.
Export those files and install the matching Python client:

```sh
mkdir -p .nomongo-t
docker cp mongot:/var/lib/mongot/config/server.crt .nomongo-t/server.crt
docker cp mongot:/var/lib/mongot/config/admin.token .nomongo-t/admin.token
python3 -m venv .venv-nomongo-t
.venv-nomongo-t/bin/python -m pip install mongot-standalone-client==0.3.0
```

Run the following example with `.venv-nomongo-t/bin/python`. For a source build, use
`docker compose -f deploy/standalone/compose.yaml up --build --wait` and install `./clients/python`.

```python
from mongot_standalone import Client, api, encode_bson, decode_bson
import time

with Client("127.0.0.1:27029", ca_file=".nomongo-t/server.crt",
            token_file=".nomongo-t/admin.token") as client:
    index = client.create_index(api.CreateIndexRequest(
        name="articles", type=api.INDEX_TYPE_SEARCH,
        definition_bson=encode_bson({"mappings": {"dynamic": True}}),
    ))
    while client.get_index(api.GetIndexRequest(index_id=index.index_id)).lifecycle_state != "READY":
        time.sleep(0.05)
    client.upsert(index.index_id, {"_id": "a", "title": "Coffee guide"})
    response = client.search(api.QueryRequest(
        index_id=index.index_id,
        query_bson=encode_bson({"text": {"path": "title", "query": "coffee"}}),
    ))
    print(decode_bson(response.batch_bson))
    client.delete(index.index_id, "a")
```

This push-only API breaks compatibility with the previous collection-backed 0.2.x API and
state layout. Use matching 0.3.x clients and a new data directory; reingest your content.
Release 0.2.x images and client packages implement the older API.

Bound concurrent writes and back off on `RESOURCE_EXHAUSTED`; see the
[backpressure contract](docs/standalone-mongot.md#backpressure-and-overload).

See [operation and write semantics](docs/standalone-mongot.md),
[architecture](docs/standalone-mongot-grpc-guide.md), [clients](clients/README.md),
and [Docker instructions](deploy/standalone/DOCKERHUB.md).
The ordinary MongoDB-connected executable remains available independently.
