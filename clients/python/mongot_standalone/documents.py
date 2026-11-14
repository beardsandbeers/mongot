"""Small conveniences for pushing replacements and deletions to one index."""

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import TYPE_CHECKING

import bson

from . import standalone_pb2 as api

if TYPE_CHECKING:
    from .client import Client


def _concern(value):
    if isinstance(value, str):
        value = api.DocumentWriteConcern.Value("DOCUMENT_WRITE_CONCERN_" + value.upper())
    if value not in (
        api.DOCUMENT_WRITE_CONCERN_VISIBLE,
        api.DOCUMENT_WRITE_CONCERN_INDEXED,
        api.DOCUMENT_WRITE_CONCERN_COMMITTED,
    ):
        raise ValueError("concern must be visible, indexed, or committed")
    return value


class DocumentCommandsMixin:
    def index(self, index_id: str) -> "Index":
        return Index(self, index_id)

    def upsert_request(self, request: api.UpsertRequest, *, timeout=None) -> api.UpsertResponse:
        return self.documents.Upsert(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def delete_request(self, request: api.DeleteRequest, *, timeout=None) -> api.DeleteResponse:
        return self.documents.Delete(
            request, timeout=self.timeout if timeout is None else timeout, metadata=self.metadata
        )

    def upsert(self, index_id: str, document: Mapping, **options) -> api.UpsertResponse:
        return self.upsert_many(index_id, [document], **options)

    def upsert_many(
        self,
        index_id: str,
        documents: Iterable[Mapping],
        *,
        concern="visible",
        generation_id="",
        timeout=None,
    ) -> api.UpsertResponse:
        return self.upsert_request(
            api.UpsertRequest(
                index_id=index_id,
                documents_bson=[bson.encode(doc) for doc in documents],
                generation_id=generation_id,
                write_concern=_concern(concern),
            ),
            timeout=timeout,
        )

    def delete(self, index_id: str, id, **options) -> api.DeleteResponse:
        return self.delete_many(index_id, [id], **options)

    def delete_many(
        self, index_id: str, ids: Iterable, *, concern="visible", generation_id="", timeout=None
    ) -> api.DeleteResponse:
        return self.delete_request(
            api.DeleteRequest(
                index_id=index_id,
                ids_bson=[bson.encode({"_id": id}) for id in ids],
                generation_id=generation_id,
                write_concern=_concern(concern),
            ),
            timeout=timeout,
        )


@dataclass(frozen=True)
class Index:
    client: "Client"
    index_id: str

    def upsert(self, document: Mapping, **options) -> api.UpsertResponse:
        return self.client.upsert(self.index_id, document, **options)

    def upsert_many(self, documents: Iterable[Mapping], **options) -> api.UpsertResponse:
        return self.client.upsert_many(self.index_id, documents, **options)

    def delete(self, id, **options) -> api.DeleteResponse:
        return self.client.delete(self.index_id, id, **options)

    def delete_many(self, ids: Iterable, **options) -> api.DeleteResponse:
        return self.client.delete_many(self.index_id, ids, **options)
