"""JSON-lines adapter used by the cross-language integration suite."""

import json
import os
import sys

import grpc
from google.protobuf.json_format import MessageToDict, ParseDict

from . import Client, api

METHODS = {
    "Upsert": ("upsert_request", api.UpsertRequest),
    "Delete": ("delete_request", api.DeleteRequest),
    "CreateIndex": ("create_index", api.CreateIndexRequest),
    "UpdateIndex": ("update_index", api.UpdateIndexRequest),
    "DropIndex": ("drop_index", api.DropIndexRequest),
    "GetIndex": ("get_index", api.GetIndexRequest),
    "GetIndexMappings": ("get_index_mappings", api.GetIndexMappingsRequest),
    "ListIndexes": ("list_indexes", api.ListIndexesRequest),
    "GetStatus": ("get_status", api.GetStatusRequest),
    "Search": ("search", api.QueryRequest),
    "VectorSearch": ("vector_search", api.QueryRequest),
    "GetMore": ("get_more", api.GetMoreRequest),
    "KillCursor": ("kill_cursor", api.KillCursorRequest),
}


def main():
    with Client(
        os.getenv("MONGOT_ADDRESS", "127.0.0.1:27029"),
        ca_file=os.getenv("MONGOT_CA"),
        token_file=os.getenv("MONGOT_TOKEN_FILE"),
        timeout=float(os.getenv("MONGOT_TIMEOUT", "30")),
    ) as client:
        for line in sys.stdin:
            try:
                call = json.loads(line)
                method, request = METHODS[call["method"]]
                response = getattr(client, method)(ParseDict(call.get("request", {}), request()))
                output = {"response": MessageToDict(response)}
            except grpc.RpcError as error:
                output = {"error": {"code": error.code().name, "message": error.details()}}
            except Exception as error:
                output = {"error": {"code": "CLIENT_ERROR", "message": str(error)}}
            print(json.dumps(output), flush=True)


if __name__ == "__main__":
    main()
