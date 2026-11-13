"""The document API must send dedicated commands, preserve IDs, and retain retry identity."""

import unittest
from types import SimpleNamespace
from uuid import UUID

import bson
import grpc
from bson import Int64, ObjectId

from mongot_standalone import DocumentCommandError, api, document_id
from mongot_standalone.documents import DocumentCommandsMixin


class TransportFailure(grpc.RpcError):
    def code(self):
        return grpc.StatusCode.UNAVAILABLE

    def details(self):
        return "connection lost after acceptance"


class RecordingClient(DocumentCommandsMixin):
    timeout = 30
    metadata = (("authorization", "Bearer test-token"),)

    def __init__(self):
        self.calls = []
        self.fail = False
        self.documents = SimpleNamespace(
            **{
                name: self.handler(name)
                for name in ("Upsert", "Delete", "DeleteByQuery", "GetDeleteByQueryResult")
            }
        )

    def handler(self, name):
        def call(request, **options):
            self.calls.append((name, request, options))
            if self.fail:
                raise TransportFailure()
            response = {
                "Upsert": api.UpsertResponse,
                "Delete": api.DeleteResponse,
                "DeleteByQuery": api.DeleteByQueryResult,
                "GetDeleteByQueryResult": api.DeleteByQueryResult,
            }[name]
            return response(collection_id=request.collection_id, request_id=request.request_id)

        return call


class DocumentHelpersTest(unittest.TestCase):
    def test_upsert_sends_document_command_without_mutation_envelope(self):
        client = RecordingClient()
        document = {"_id": "a", "binary": bson.Binary(b"\x00\xff"), "large": Int64(2**60 + 1)}

        result = client.collection("collection").upsert(document, request_id="stable", timeout=5)

        name, request, options = client.calls[0]
        self.assertEqual(name, "Upsert")
        self.assertIsInstance(request, api.UpsertRequest)
        self.assertEqual(bson.encode(bson.decode(request.documents_bson[0])), bson.encode(document))
        self.assertEqual(request.concern, api.DOCUMENT_WRITE_CONCERN_VISIBLE)
        self.assertEqual(options, {"timeout": 5, "metadata": client.metadata})
        self.assertEqual(result.request_id, "stable")

    def test_delete_many_preserves_bson_id_types(self):
        client = RecordingClient()
        oid = ObjectId()

        client.collection("c").delete_many(["a", oid, 1, Int64(1), 2**40], concern="committed")

        name, request, options = client.calls[0]
        self.assertEqual(name, "Delete")
        self.assertEqual(
            [id.WhichOneof("value") for id in request.ids],
            ["string_value", "object_id", "int32_value", "int64_value", "int64_value"],
        )
        self.assertEqual(request.ids[1].object_id, str(oid))
        self.assertEqual(request.concern, api.DOCUMENT_WRITE_CONCERN_COMMITTED)
        self.assertEqual(options["timeout"], 30)

    def test_query_delete_is_one_server_command_and_progress_lookup(self):
        client = RecordingClient()
        collection = client.collection("c")
        query = {"text": {"path": "status", "query": "expired"}}

        collection.delete_by_query("index", query, request_id="sweep", batch_size=2)
        collection.delete_by_query_result("sweep")

        self.assertEqual(
            [call[0] for call in client.calls], ["DeleteByQuery", "GetDeleteByQueryResult"]
        )
        self.assertEqual(bson.decode(client.calls[0][1].query_bson), query)
        self.assertEqual(client.calls[0][1].batch_size, 2)
        self.assertEqual(client.calls[1][1].collection_id, "c")
        self.assertEqual(client.calls[1][1].request_id, "sweep")

    def test_failure_exposes_generated_request_id_without_automatic_retry(self):
        client = RecordingClient()
        client.fail = True

        with self.assertRaises(DocumentCommandError) as caught:
            client.delete("c", "a")

        error = caught.exception
        self.assertEqual(error.code(), grpc.StatusCode.UNAVAILABLE)
        self.assertEqual(error.collection_id, "c")
        self.assertEqual(error.request_id, client.calls[0][1].request_id)
        UUID(error.request_id)
        self.assertEqual(len(client.calls), 1)
        self.assertIn(error.request_id, str(error))

    def test_bound_collections_do_not_change_each_others_target(self):
        client = RecordingClient()
        first = client.collection("first")
        second = client.collection("second")

        second.delete("b")
        first.delete("a")

        self.assertEqual([call[1].collection_id for call in client.calls], ["second", "first"])

    def test_invalid_ids_and_concerns_fail_before_sending_commands(self):
        client = RecordingClient()
        with self.assertRaises(TypeError):
            client.delete("c", True)
        with self.assertRaises(TypeError):
            document_id({"_id": "a"})
        with self.assertRaises(ValueError):
            document_id(2**63)
        with self.assertRaises(ValueError):
            client.upsert("c", {"_id": "a"}, concern="anything")
        self.assertEqual(client.calls, [])


if __name__ == "__main__":
    unittest.main()
