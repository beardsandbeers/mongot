"""Document-oriented helpers over the dedicated server RPCs."""

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import TYPE_CHECKING
from uuid import uuid4

import bson
import grpc
from bson import Int64, ObjectId

from . import standalone_pb2 as api

if TYPE_CHECKING:
    from .client import Client


class DocumentCommandError(grpc.RpcError):
    """An RPC failure retaining the request identity needed for inspection/resumption."""

    def __init__(self, error: grpc.RpcError, collection_id: str, request_id: str):
        self.original_error = error
        self.collection_id = collection_id
        self.request_id = request_id
        super().__init__(f"{error.details()} (request_id={request_id})")

    def code(self):
        return self.original_error.code()

    def details(self):
        return self.original_error.details()

    def __getattr__(self, name):
        return getattr(self.original_error, name)


def document_id(value) -> api.DocumentId:
    """Build a typed ID, preserving BSON ObjectId/int32/int64 identity."""
    if isinstance(value, ObjectId):
        return api.DocumentId(object_id=str(value))
    if isinstance(value, str):
        return api.DocumentId(string_value=value)
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError("Document IDs must be strings, ObjectIds, or int32/int64 integers")
    if not -(2**63) <= value < 2**63:
        raise ValueError("Document ID is outside the signed int64 range")
    if isinstance(value, Int64) or not -(2**31) <= value < 2**31:
        return api.DocumentId(int64_value=value)
    return api.DocumentId(int32_value=value)


def _concern(value):
    if isinstance(value, str):
        try:
            return api.DocumentWriteConcern.Value("DOCUMENT_WRITE_CONCERN_" + value.upper())
        except ValueError:
            raise ValueError("concern must be 'visible', 'indexed', or 'committed'") from None
    if value not in (
        api.DOCUMENT_WRITE_CONCERN_VISIBLE,
        api.DOCUMENT_WRITE_CONCERN_INDEXED,
        api.DOCUMENT_WRITE_CONCERN_COMMITTED,
    ):
        raise ValueError("Unknown document write concern")
    return value


def _request_id(value):
    return uuid4().hex if value is None else value


class DocumentCommandsMixin:
    def collection(self, collection_id: str) -> "Collection":
        """Bind the collection once: docs.upsert(document), docs.delete(id), etc."""
        return Collection(self, collection_id)

    def _document_rpc(self, method, request, timeout):
        try:
            return getattr(self.documents, method)(
                request,
                timeout=self.timeout if timeout is None else timeout,
                metadata=self.metadata,
            )
        except grpc.RpcError as error:
            raise DocumentCommandError(error, request.collection_id, request.request_id) from error

    def upsert_request(self, request: api.UpsertRequest, *, timeout=None) -> api.UpsertResponse:
        return self._document_rpc("Upsert", request, timeout)

    def delete_request(self, request: api.DeleteRequest, *, timeout=None) -> api.DeleteResponse:
        return self._document_rpc("Delete", request, timeout)

    def delete_by_query_request(
        self, request: api.DeleteByQueryRequest, *, timeout=None
    ) -> api.DeleteByQueryResult:
        return self._document_rpc("DeleteByQuery", request, timeout)

    def get_delete_by_query_result(
        self, request: api.GetDeleteByQueryResultRequest, *, timeout=None
    ) -> api.DeleteByQueryResult:
        return self._document_rpc("GetDeleteByQueryResult", request, timeout)

    def upsert(
        self,
        collection_id: str,
        document: Mapping,
        *,
        request_id=None,
        concern="visible",
        timeout=None,
    ) -> api.UpsertResponse:
        return self.upsert_many(
            collection_id, [document], request_id=request_id, concern=concern, timeout=timeout
        )

    def upsert_many(
        self,
        collection_id: str,
        documents: Iterable[Mapping],
        *,
        request_id=None,
        concern="visible",
        timeout=None,
    ) -> api.UpsertResponse:
        return self.upsert_request(
            api.UpsertRequest(
                collection_id=collection_id,
                request_id=_request_id(request_id),
                documents_bson=[bson.encode(doc) for doc in documents],
                concern=_concern(concern),
            ),
            timeout=timeout,
        )

    def delete(
        self, collection_id: str, id, *, request_id=None, concern="visible", timeout=None
    ) -> api.DeleteResponse:
        return self.delete_many(
            collection_id, [id], request_id=request_id, concern=concern, timeout=timeout
        )

    def delete_many(
        self, collection_id: str, ids: Iterable, *, request_id=None, concern="visible", timeout=None
    ) -> api.DeleteResponse:
        return self.delete_request(
            api.DeleteRequest(
                collection_id=collection_id,
                request_id=_request_id(request_id),
                ids=[document_id(id) for id in ids],
                concern=_concern(concern),
            ),
            timeout=timeout,
        )

    def delete_by_query(
        self,
        collection_id: str,
        index_id: str,
        query: Mapping,
        *,
        request_id=None,
        batch_size=100,
        concern="visible",
        generation_id="",
        timeout=None,
    ) -> api.DeleteByQueryResult:
        return self.delete_by_query_request(
            api.DeleteByQueryRequest(
                collection_id=collection_id,
                request_id=_request_id(request_id),
                index_id=index_id,
                query_bson=bson.encode(query),
                batch_size=batch_size,
                concern=_concern(concern),
                generation_id=generation_id,
            ),
            timeout=timeout,
        )

    def delete_by_query_result(
        self, collection_id: str, request_id: str, *, timeout=None
    ) -> api.DeleteByQueryResult:
        return self.get_delete_by_query_result(
            api.GetDeleteByQueryResultRequest(collection_id=collection_id, request_id=request_id),
            timeout=timeout,
        )


@dataclass(frozen=True)
class Collection:
    client: "Client"
    collection_id: str

    def upsert(self, document: Mapping, **options) -> api.UpsertResponse:
        return self.client.upsert(self.collection_id, document, **options)

    def upsert_many(self, documents: Iterable[Mapping], **options) -> api.UpsertResponse:
        return self.client.upsert_many(self.collection_id, documents, **options)

    def delete(self, id, **options) -> api.DeleteResponse:
        return self.client.delete(self.collection_id, id, **options)

    def delete_many(self, ids: Iterable, **options) -> api.DeleteResponse:
        return self.client.delete_many(self.collection_id, ids, **options)

    def delete_by_query(self, index_id: str, query: Mapping, **options) -> api.DeleteByQueryResult:
        return self.client.delete_by_query(self.collection_id, index_id, query, **options)

    def delete_by_query_result(self, request_id: str, **options) -> api.DeleteByQueryResult:
        return self.client.delete_by_query_result(self.collection_id, request_id, **options)
