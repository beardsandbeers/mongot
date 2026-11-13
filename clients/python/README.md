# Standalone mongot Python client

Install the wheel or source archive with pip. Python 3.10+ is required; no repository checkout,
protoc, or MongoDB server is needed. Dependencies are resolved by pip.

```sh
python -m pip install mongot_standalone_client-0.1.0-py3-none-any.whl
```

```python
from mongot_standalone import Client, api

with Client("localhost:27029", ca_file="server.crt", token_file="admin.token") as client:
    status = client.get_status(api.GetStatusRequest())
    print(status.instance_id)
```

All 16 RPCs have typed request/response wrappers. `encode_bson` and `decode_bson` preserve BSON
types. Omit CA/token only for a plaintext development listener. Callers supply stable request
IDs and inspect every write item's state/status; no mutation is automatically retried.

Full API examples: https://github.com/beardsandbeers/mongot/blob/nomongo/clients/README.md
