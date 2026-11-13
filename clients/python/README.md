# Standalone mongot Python client

Install from [PyPI](https://pypi.org/project/mongot-standalone-client/) with pip. Python 3.10+ is
required; no repository checkout, protoc, or MongoDB server is needed. Dependencies are resolved
by pip. Downloaded wheels and source archives can also be installed directly.

```sh
python -m pip install mongot-standalone-client==0.2.0
```

```python
from mongot_standalone import Client, api

with Client("localhost:27029", ca_file="server.crt", token_file="admin.token") as client:
    status = client.get_status(api.GetStatusRequest())
    print(status.instance_id)
```

The 0.2.0 server provides document-oriented RPCs. Python usage:

```python
with Client("localhost:27029", ca_file="server.crt", token_file="admin.token") as client:
    docs = client.collection(collection_id)
    docs.upsert({"_id": "a", "title": "expired"})
    docs.upsert_many([{"_id": "b", "title": "expired"}])
    docs.delete("a")
    docs.delete_many(["b", "c"])
    result = docs.delete_by_query(index_id, {"text": {"path": "title", "query": "expired"}})
    print(result.deleted_count, result.conflict_count)
```

Commands wait for search visibility by default. Upsert replaces complete documents; delete takes
IDs directly. Query deletion runs on the server with a durable snapshot selection and skips
concurrently changed documents. Preserve request IDs for retries; `DocumentCommandError` carries
the request ID even when the call times out. These commands require a 0.2.0 server.

All 20 RPCs have typed request/response wrappers. `encode_bson` and `decode_bson` preserve BSON
types. Omit CA/token only for a plaintext development listener. Legacy Write/WriteBatch callers
supply stable request IDs and inspect every item's state/status; no mutation is automatically retried.

Full API examples: https://github.com/beardsandbeers/mongot/blob/nomongo/clients/README.md
