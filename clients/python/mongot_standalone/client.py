"""Typed requests/responses, BSON helpers, per-call deadlines and TLS/bearer auth."""

import math
from collections.abc import Mapping
from pathlib import Path

import bson
import grpc

from . import standalone_pb2 as api
from . import standalone_pb2_grpc as rpc
from .documents import DocumentCommandsMixin


def encode_bson(document: Mapping) -> bytes:
    return bson.encode(document)


def decode_bson(data: bytes) -> dict:
    return bson.decode(data)


class Client(DocumentCommandsMixin):
    def __init__(self, address="127.0.0.1:27029", *, ca_file=None, token_file=None, timeout=30):
        if timeout <= 0 or not math.isfinite(timeout):
            raise ValueError("timeout must be positive and finite")
        if token_file and not ca_file:
            raise ValueError("bearer authentication requires TLS")
        self.timeout = timeout
        self.metadata = (
            (("authorization", "Bearer " + Path(token_file).read_text().strip()),)
            if token_file
            else ()
        )
        options = (("grpc.max_receive_message_length", 64 * 1024 * 1024),)
        self.channel = (
            grpc.secure_channel(
                address, grpc.ssl_channel_credentials(Path(ca_file).read_bytes()), options
            )
            if ca_file
            else grpc.insecure_channel(address, options)
        )
        self.catalog = rpc.CatalogServiceStub(self.channel)
        self.documents = rpc.DocumentServiceStub(self.channel)
        self.operations = rpc.OperationsServiceStub(self.channel)
        self.queries = rpc.QueryServiceStub(self.channel)

    def close(self):
        self.channel.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def create_index(
        self, request: api.CreateIndexRequest, *, timeout=None
    ) -> api.IndexDescription:
        return self.catalog.CreateIndex(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def update_index(
        self, request: api.UpdateIndexRequest, *, timeout=None
    ) -> api.IndexDescription:
        return self.catalog.UpdateIndex(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def drop_index(self, request: api.DropIndexRequest, *, timeout=None) -> api.IndexDescription:
        return self.catalog.DropIndex(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def get_index(self, request: api.GetIndexRequest, *, timeout=None) -> api.IndexDescription:
        return self.catalog.GetIndex(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def list_indexes(
        self, request: api.ListIndexesRequest, *, timeout=None
    ) -> api.ListIndexesResponse:
        return self.catalog.ListIndexes(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def get_status(self, request: api.GetStatusRequest, *, timeout=None) -> api.ServiceStatus:
        return self.operations.GetStatus(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def search(self, request: api.QueryRequest, *, timeout=None) -> api.QueryResponse:
        return self.queries.Search(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def vector_search(self, request: api.QueryRequest, *, timeout=None) -> api.QueryResponse:
        return self.queries.VectorSearch(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def get_more(self, request: api.GetMoreRequest, *, timeout=None) -> api.QueryResponse:
        return self.queries.GetMore(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def kill_cursor(
        self, request: api.KillCursorRequest, *, timeout=None
    ) -> api.KillCursorResponse:
        return self.queries.KillCursor(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )
