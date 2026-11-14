from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class IndexType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    INDEX_TYPE_UNSPECIFIED: _ClassVar[IndexType]
    INDEX_TYPE_SEARCH: _ClassVar[IndexType]
    INDEX_TYPE_VECTOR_SEARCH: _ClassVar[IndexType]

class DocumentWriteConcern(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DOCUMENT_WRITE_CONCERN_VISIBLE: _ClassVar[DocumentWriteConcern]
    DOCUMENT_WRITE_CONCERN_INDEXED: _ClassVar[DocumentWriteConcern]
    DOCUMENT_WRITE_CONCERN_COMMITTED: _ClassVar[DocumentWriteConcern]
INDEX_TYPE_UNSPECIFIED: IndexType
INDEX_TYPE_SEARCH: IndexType
INDEX_TYPE_VECTOR_SEARCH: IndexType
DOCUMENT_WRITE_CONCERN_VISIBLE: DocumentWriteConcern
DOCUMENT_WRITE_CONCERN_INDEXED: DocumentWriteConcern
DOCUMENT_WRITE_CONCERN_COMMITTED: DocumentWriteConcern

class CreateIndexRequest(_message.Message):
    __slots__ = ("name", "type", "definition_bson")
    NAME_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    DEFINITION_BSON_FIELD_NUMBER: _ClassVar[int]
    name: str
    type: IndexType
    definition_bson: bytes
    def __init__(self, name: _Optional[str] = ..., type: _Optional[_Union[IndexType, str]] = ..., definition_bson: _Optional[bytes] = ...) -> None: ...

class UpdateIndexRequest(_message.Message):
    __slots__ = ("index_id", "expected_revision", "definition_bson")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    DEFINITION_BSON_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    expected_revision: int
    definition_bson: bytes
    def __init__(self, index_id: _Optional[str] = ..., expected_revision: _Optional[int] = ..., definition_bson: _Optional[bytes] = ...) -> None: ...

class DropIndexRequest(_message.Message):
    __slots__ = ("index_id", "expected_revision")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    expected_revision: int
    def __init__(self, index_id: _Optional[str] = ..., expected_revision: _Optional[int] = ...) -> None: ...

class GetIndexRequest(_message.Message):
    __slots__ = ("index_id",)
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    def __init__(self, index_id: _Optional[str] = ...) -> None: ...

class GetIndexMappingsRequest(_message.Message):
    __slots__ = ("index_id", "generation_id")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    generation_id: str
    def __init__(self, index_id: _Optional[str] = ..., generation_id: _Optional[str] = ...) -> None: ...

class GetIndexMappingsResponse(_message.Message):
    __slots__ = ("index_id", "generation_id", "active_revision", "mappings_json", "definition_json")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_REVISION_FIELD_NUMBER: _ClassVar[int]
    MAPPINGS_JSON_FIELD_NUMBER: _ClassVar[int]
    DEFINITION_JSON_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    generation_id: str
    active_revision: int
    mappings_json: str
    definition_json: str
    def __init__(self, index_id: _Optional[str] = ..., generation_id: _Optional[str] = ..., active_revision: _Optional[int] = ..., mappings_json: _Optional[str] = ..., definition_json: _Optional[str] = ...) -> None: ...

class ListIndexesRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListIndexesResponse(_message.Message):
    __slots__ = ("indexes",)
    INDEXES_FIELD_NUMBER: _ClassVar[int]
    indexes: _containers.RepeatedCompositeFieldContainer[IndexDescription]
    def __init__(self, indexes: _Optional[_Iterable[_Union[IndexDescription, _Mapping]]] = ...) -> None: ...

class IndexDescription(_message.Message):
    __slots__ = ("index_id", "name", "type", "desired_revision", "desired_definition_bson", "lifecycle_state", "generation_id", "active_revision", "failure_message")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    DESIRED_REVISION_FIELD_NUMBER: _ClassVar[int]
    DESIRED_DEFINITION_BSON_FIELD_NUMBER: _ClassVar[int]
    LIFECYCLE_STATE_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_REVISION_FIELD_NUMBER: _ClassVar[int]
    FAILURE_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    name: str
    type: IndexType
    desired_revision: int
    desired_definition_bson: bytes
    lifecycle_state: str
    generation_id: str
    active_revision: int
    failure_message: str
    def __init__(self, index_id: _Optional[str] = ..., name: _Optional[str] = ..., type: _Optional[_Union[IndexType, str]] = ..., desired_revision: _Optional[int] = ..., desired_definition_bson: _Optional[bytes] = ..., lifecycle_state: _Optional[str] = ..., generation_id: _Optional[str] = ..., active_revision: _Optional[int] = ..., failure_message: _Optional[str] = ...) -> None: ...

class UpsertRequest(_message.Message):
    __slots__ = ("index_id", "documents_bson", "generation_id", "write_concern")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    DOCUMENTS_BSON_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    WRITE_CONCERN_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    documents_bson: _containers.RepeatedScalarFieldContainer[bytes]
    generation_id: str
    write_concern: DocumentWriteConcern
    def __init__(self, index_id: _Optional[str] = ..., documents_bson: _Optional[_Iterable[bytes]] = ..., generation_id: _Optional[str] = ..., write_concern: _Optional[_Union[DocumentWriteConcern, str]] = ...) -> None: ...

class UpsertResponse(_message.Message):
    __slots__ = ("index_id", "generation_id", "indexed_count")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    INDEXED_COUNT_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    generation_id: str
    indexed_count: int
    def __init__(self, index_id: _Optional[str] = ..., generation_id: _Optional[str] = ..., indexed_count: _Optional[int] = ...) -> None: ...

class DeleteRequest(_message.Message):
    __slots__ = ("index_id", "ids_bson", "generation_id", "write_concern")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    IDS_BSON_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    WRITE_CONCERN_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    ids_bson: _containers.RepeatedScalarFieldContainer[bytes]
    generation_id: str
    write_concern: DocumentWriteConcern
    def __init__(self, index_id: _Optional[str] = ..., ids_bson: _Optional[_Iterable[bytes]] = ..., generation_id: _Optional[str] = ..., write_concern: _Optional[_Union[DocumentWriteConcern, str]] = ...) -> None: ...

class DeleteResponse(_message.Message):
    __slots__ = ("index_id", "generation_id", "processed_count")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    PROCESSED_COUNT_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    generation_id: str
    processed_count: int
    def __init__(self, index_id: _Optional[str] = ..., generation_id: _Optional[str] = ..., processed_count: _Optional[int] = ...) -> None: ...

class QueryRequest(_message.Message):
    __slots__ = ("index_id", "query_bson", "batch_size", "generation_id")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    QUERY_BSON_FIELD_NUMBER: _ClassVar[int]
    BATCH_SIZE_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    query_bson: bytes
    batch_size: int
    generation_id: str
    def __init__(self, index_id: _Optional[str] = ..., query_bson: _Optional[bytes] = ..., batch_size: _Optional[int] = ..., generation_id: _Optional[str] = ...) -> None: ...

class QueryResponse(_message.Message):
    __slots__ = ("cursor_id", "batch_bson", "metadata_bson", "generation_id")
    CURSOR_ID_FIELD_NUMBER: _ClassVar[int]
    BATCH_BSON_FIELD_NUMBER: _ClassVar[int]
    METADATA_BSON_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    cursor_id: int
    batch_bson: bytes
    metadata_bson: bytes
    generation_id: str
    def __init__(self, cursor_id: _Optional[int] = ..., batch_bson: _Optional[bytes] = ..., metadata_bson: _Optional[bytes] = ..., generation_id: _Optional[str] = ...) -> None: ...

class GetMoreRequest(_message.Message):
    __slots__ = ("cursor_id", "batch_size")
    CURSOR_ID_FIELD_NUMBER: _ClassVar[int]
    BATCH_SIZE_FIELD_NUMBER: _ClassVar[int]
    cursor_id: int
    batch_size: int
    def __init__(self, cursor_id: _Optional[int] = ..., batch_size: _Optional[int] = ...) -> None: ...

class KillCursorRequest(_message.Message):
    __slots__ = ("cursor_id",)
    CURSOR_ID_FIELD_NUMBER: _ClassVar[int]
    cursor_id: int
    def __init__(self, cursor_id: _Optional[int] = ...) -> None: ...

class KillCursorResponse(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetStatusRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ServiceStatus(_message.Message):
    __slots__ = ("management_ready", "failure_message", "indexes", "free_disk_bytes", "metrics")
    MANAGEMENT_READY_FIELD_NUMBER: _ClassVar[int]
    FAILURE_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    INDEXES_FIELD_NUMBER: _ClassVar[int]
    FREE_DISK_BYTES_FIELD_NUMBER: _ClassVar[int]
    METRICS_FIELD_NUMBER: _ClassVar[int]
    management_ready: bool
    failure_message: str
    indexes: _containers.RepeatedCompositeFieldContainer[IndexDescription]
    free_disk_bytes: int
    metrics: _containers.RepeatedCompositeFieldContainer[ServiceMetric]
    def __init__(self, management_ready: _Optional[bool] = ..., failure_message: _Optional[str] = ..., indexes: _Optional[_Iterable[_Union[IndexDescription, _Mapping]]] = ..., free_disk_bytes: _Optional[int] = ..., metrics: _Optional[_Iterable[_Union[ServiceMetric, _Mapping]]] = ...) -> None: ...

class ServiceMetric(_message.Message):
    __slots__ = ("name", "labels", "measurements")
    class LabelsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: str
        def __init__(self, key: _Optional[str] = ..., value: _Optional[str] = ...) -> None: ...
    class MeasurementsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: float
        def __init__(self, key: _Optional[str] = ..., value: _Optional[float] = ...) -> None: ...
    NAME_FIELD_NUMBER: _ClassVar[int]
    LABELS_FIELD_NUMBER: _ClassVar[int]
    MEASUREMENTS_FIELD_NUMBER: _ClassVar[int]
    name: str
    labels: _containers.ScalarMap[str, str]
    measurements: _containers.ScalarMap[str, float]
    def __init__(self, name: _Optional[str] = ..., labels: _Optional[_Mapping[str, str]] = ..., measurements: _Optional[_Mapping[str, float]] = ...) -> None: ...
