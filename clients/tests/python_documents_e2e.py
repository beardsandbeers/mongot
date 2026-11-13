#!/usr/bin/env python3
"""Exercise the ergonomic Python API against a real standalone server."""

import argparse
import tempfile
import time
from pathlib import Path

import bson
import grpc
from bson import Int64, ObjectId
from e2e import Server, check
from mongot_standalone import Client, DocumentCommandError, api, decode_bson, encode_bson


def ready(client, index_id):
    until = time.monotonic() + 30
    while time.monotonic() < until:
        index = client.get_index(api.GetIndexRequest(index_id=index_id))
        check(not index.failure_message, index.failure_message)
        if index.lifecycle_state == "READY":
            return index
        time.sleep(0.05)
    raise TimeoutError("Index did not become ready")


def results(client, index_id, term):
    response = client.search(
        api.QueryRequest(
            index_id=index_id, query_bson=encode_bson({"text": {"path": "title", "query": term}})
        )
    )
    rows = decode_bson(response.batch_bson)["results"]
    while response.cursor_id:
        response = client.get_more(api.GetMoreRequest(cursor_id=response.cursor_id))
        rows.extend(decode_bson(response.batch_bson)["results"])
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher", required=True)
    args = parser.parse_args()
    work = Path(tempfile.mkdtemp(prefix="mongot-python-documents-"))
    with Server(args.launcher, work, False) as server:
        env = server.environment
        with Client(
            env["MONGOT_ADDRESS"], ca_file=env["MONGOT_CA"], token_file=env["MONGOT_TOKEN_FILE"]
        ) as client:
            collection = client.create_collection(
                api.CreateCollectionRequest(
                    request_id="collection", database="documents", name="documents"
                )
            )
            index = client.create_index(
                api.CreateIndexRequest(
                    collection_id=collection.collection_id,
                    request_id="index",
                    name="text",
                    type=api.INDEX_TYPE_SEARCH,
                    definition_bson=encode_bson({"mappings": {"dynamic": True}}),
                )
            )
            ready(client, index.index_id)
            docs = client.collection(collection.collection_id)
            oid = ObjectId()
            check(
                docs.upsert({"_id": "a", "title": "before"}).upserted_count == 1,
                "Single upsert failed",
            )
            docs.upsert({"_id": "a", "title": "remove"})
            check(
                not results(client, index.index_id, "before"),
                "Upsert was not a visible replacement",
            )
            docs.upsert_many(
                [
                    {"_id": oid, "title": "remove"},
                    {"_id": 1, "title": "keep"},
                    {"_id": Int64(1), "title": "keep"},
                    {"_id": "b", "title": "remove"},
                ]
            )
            check(docs.delete(oid).deleted_count == 1, "ObjectId delete failed")
            check(
                docs.delete_many([1, "missing"]).deleted_count == 1, "Delete count/type was wrong"
            )
            survivors = results(client, index.index_id, "keep")
            check(
                len(survivors) == 1 and isinstance(survivors[0]["_id"], Int64),
                "int64 ID was not preserved",
            )
            deleted = docs.delete_by_query(
                index.index_id,
                {"text": {"path": "title", "query": "remove"}},
                request_id="sweep",
                batch_size=1,
            )
            check(deleted.complete and deleted.deleted_count == 2, "Server query deletion failed")
            docs.upsert({"_id": "new", "title": "remove"})
            retry = docs.delete_by_query(
                index.index_id,
                {"text": {"path": "title", "query": "remove"}},
                request_id="sweep",
                batch_size=1,
            )
            check(
                retry == deleted and docs.delete_by_query_result("sweep") == deleted,
                "Query retry changed outcome",
            )
            check(
                len(results(client, index.index_id, "remove")) == 1,
                "Query retry deleted a new match",
            )
            try:
                docs.upsert_many([{"_id": "valid"}, {"missing": "id"}], request_id="partial")
                raise AssertionError("Partial command error was hidden")
            except DocumentCommandError as error:
                check(
                    error.code() == grpc.StatusCode.INVALID_ARGUMENT
                    and error.request_id == "partial",
                    "Command exception lost its retry identity",
                )
                outcome = client.get_write_result(
                    api.GetWriteResultRequest(
                        collection_id=collection.collection_id, request_id=error.request_id
                    )
                )
                check(
                    outcome.complete
                    and outcome.results[0].state == api.WRITE_ITEM_STATE_INDEXED
                    and outcome.results[1].state == api.WRITE_ITEM_STATE_REJECTED,
                    "Partial outcomes are missing",
                )
    print("PASS: ergonomic Python upsert/delete/query commands over authenticated TLS")


if __name__ == "__main__":
    main()
