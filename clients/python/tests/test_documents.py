"""Bound index helpers preserve BSON identity and never invent durable request IDs."""

import unittest
from types import SimpleNamespace

import bson
from bson import Int64, ObjectId

from mongot_standalone import api
from mongot_standalone.documents import DocumentCommandsMixin


class RecordingClient(DocumentCommandsMixin):
    timeout = 30
    metadata = ()

    def __init__(self):
        self.calls = []

        def record(request, **options):
            self.calls.append((request, options))
            return None

        self.documents = SimpleNamespace(Upsert=record, Delete=record)


class IndexHelpersTest(unittest.TestCase):
    def test_bound_helpers_target_one_index_and_preserve_bson(self):
        client = RecordingClient()
        oid = ObjectId()
        client.index("first").upsert({"_id": oid, "big": Int64(2**60 + 1)}, concern="committed")
        client.index("second").delete_many([oid, Int64(1), 1])
        upsert = client.calls[0][0]
        self.assertEqual(upsert.index_id, "first")
        self.assertEqual(bson.decode(upsert.documents_bson[0])["big"], 2**60 + 1)
        self.assertEqual(upsert.write_concern, api.DOCUMENT_WRITE_CONCERN_COMMITTED)
        delete = client.calls[1][0]
        self.assertEqual(delete.index_id, "second")
        self.assertIsInstance(bson.decode(delete.ids_bson[1])["_id"], Int64)
        self.assertNotIsInstance(bson.decode(delete.ids_bson[2])["_id"], Int64)
        self.assertFalse(hasattr(upsert, "request_id"))

    def test_generation_and_timeout_are_forwarded_without_retries(self):
        client = RecordingClient()
        client.upsert("index", {"_id": "a"}, generation_id="generation", timeout=5)
        self.assertEqual(client.calls[0][0].generation_id, "generation")
        self.assertEqual(client.calls[0][1]["timeout"], 5)
        with self.assertRaises(ValueError):
            client.upsert("index", {"_id": "a"}, concern="anything")
        self.assertEqual(len(client.calls), 1)


if __name__ == "__main__":
    unittest.main()
