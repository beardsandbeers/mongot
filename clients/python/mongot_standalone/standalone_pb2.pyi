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

class MutationType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MUTATION_TYPE_UNSPECIFIED: _ClassVar[MutationType]
    MUTATION_TYPE_INSERT: _ClassVar[MutationType]
    MUTATION_TYPE_UPSERT: _ClassVar[MutationType]
    MUTATION_TYPE_DELETE: _ClassVar[MutationType]

class WriteItemState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    WRITE_ITEM_STATE_UNSPECIFIED: _ClassVar[WriteItemState]
    WRITE_ITEM_STATE_REJECTED: _ClassVar[WriteItemState]
    WRITE_ITEM_STATE_PENDING_INDEXING: _ClassVar[WriteItemState]
    WRITE_ITEM_STATE_INDEXED: _ClassVar[WriteItemState]
    WRITE_ITEM_STATE_INDEXING_FAILED: _ClassVar[WriteItemState]

class SequenceCondition(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SEQUENCE_CONDITION_UNSPECIFIED: _ClassVar[SequenceCondition]
    SEQUENCE_CONDITION_APPLIED: _ClassVar[SequenceCondition]
    SEQUENCE_CONDITION_COMMITTED: _ClassVar[SequenceCondition]
    SEQUENCE_CONDITION_VISIBLE: _ClassVar[SequenceCondition]

class DocumentWriteConcern(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DOCUMENT_WRITE_CONCERN_VISIBLE: _ClassVar[DocumentWriteConcern]
    DOCUMENT_WRITE_CONCERN_INDEXED: _ClassVar[DocumentWriteConcern]
    DOCUMENT_WRITE_CONCERN_COMMITTED: _ClassVar[DocumentWriteConcern]
INDEX_TYPE_UNSPECIFIED: IndexType
INDEX_TYPE_SEARCH: IndexType
INDEX_TYPE_VECTOR_SEARCH: IndexType
MUTATION_TYPE_UNSPECIFIED: MutationType
MUTATION_TYPE_INSERT: MutationType
MUTATION_TYPE_UPSERT: MutationType
MUTATION_TYPE_DELETE: MutationType
WRITE_ITEM_STATE_UNSPECIFIED: WriteItemState
WRITE_ITEM_STATE_REJECTED: WriteItemState
WRITE_ITEM_STATE_PENDING_INDEXING: WriteItemState
WRITE_ITEM_STATE_INDEXED: WriteItemState
WRITE_ITEM_STATE_INDEXING_FAILED: WriteItemState
SEQUENCE_CONDITION_UNSPECIFIED: SequenceCondition
SEQUENCE_CONDITION_APPLIED: SequenceCondition
SEQUENCE_CONDITION_COMMITTED: SequenceCondition
SEQUENCE_CONDITION_VISIBLE: SequenceCondition
DOCUMENT_WRITE_CONCERN_VISIBLE: DocumentWriteConcern
DOCUMENT_WRITE_CONCERN_INDEXED: DocumentWriteConcern
DOCUMENT_WRITE_CONCERN_COMMITTED: DocumentWriteConcern

class GetStatusRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ServiceStatus(_message.Message):
    __slots__ = ("instance_id", "management_ready", "failure_message", "store_schema_version", "retry_retention_millis", "logical_log_bytes", "retry_requests", "retention_pins", "source_bytes", "store_file_bytes", "sqlite_wal_bytes", "free_disk_bytes", "indexes", "metrics")
    INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    MANAGEMENT_READY_FIELD_NUMBER: _ClassVar[int]
    FAILURE_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    STORE_SCHEMA_VERSION_FIELD_NUMBER: _ClassVar[int]
    RETRY_RETENTION_MILLIS_FIELD_NUMBER: _ClassVar[int]
    LOGICAL_LOG_BYTES_FIELD_NUMBER: _ClassVar[int]
    RETRY_REQUESTS_FIELD_NUMBER: _ClassVar[int]
    RETENTION_PINS_FIELD_NUMBER: _ClassVar[int]
    SOURCE_BYTES_FIELD_NUMBER: _ClassVar[int]
    STORE_FILE_BYTES_FIELD_NUMBER: _ClassVar[int]
    SQLITE_WAL_BYTES_FIELD_NUMBER: _ClassVar[int]
    FREE_DISK_BYTES_FIELD_NUMBER: _ClassVar[int]
    INDEXES_FIELD_NUMBER: _ClassVar[int]
    METRICS_FIELD_NUMBER: _ClassVar[int]
    instance_id: str
    management_ready: bool
    failure_message: str
    store_schema_version: int
    retry_retention_millis: int
    logical_log_bytes: int
    retry_requests: int
    retention_pins: int
    source_bytes: int
    store_file_bytes: int
    sqlite_wal_bytes: int
    free_disk_bytes: int
    indexes: _containers.RepeatedCompositeFieldContainer[IndexDescription]
    metrics: _containers.RepeatedCompositeFieldContainer[ServiceMetric]
    def __init__(self, instance_id: _Optional[str] = ..., management_ready: _Optional[bool] = ..., failure_message: _Optional[str] = ..., store_schema_version: _Optional[int] = ..., retry_retention_millis: _Optional[int] = ..., logical_log_bytes: _Optional[int] = ..., retry_requests: _Optional[int] = ..., retention_pins: _Optional[int] = ..., source_bytes: _Optional[int] = ..., store_file_bytes: _Optional[int] = ..., sqlite_wal_bytes: _Optional[int] = ..., free_disk_bytes: _Optional[int] = ..., indexes: _Optional[_Iterable[_Union[IndexDescription, _Mapping]]] = ..., metrics: _Optional[_Iterable[_Union[ServiceMetric, _Mapping]]] = ...) -> None: ...

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

class CreateCollectionRequest(_message.Message):
    __slots__ = ("request_id", "database", "name")
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    DATABASE_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    request_id: str
    database: str
    name: str
    def __init__(self, request_id: _Optional[str] = ..., database: _Optional[str] = ..., name: _Optional[str] = ...) -> None: ...

class GetCollectionRequest(_message.Message):
    __slots__ = ("collection_id",)
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    def __init__(self, collection_id: _Optional[str] = ...) -> None: ...

class CollectionDescription(_message.Message):
    __slots__ = ("collection_id", "database", "name", "accepted_sequence")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    DATABASE_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    ACCEPTED_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    database: str
    name: str
    accepted_sequence: int
    def __init__(self, collection_id: _Optional[str] = ..., database: _Optional[str] = ..., name: _Optional[str] = ..., accepted_sequence: _Optional[int] = ...) -> None: ...

class CreateIndexRequest(_message.Message):
    __slots__ = ("request_id", "collection_id", "name", "type", "definition_bson")
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    DEFINITION_BSON_FIELD_NUMBER: _ClassVar[int]
    request_id: str
    collection_id: str
    name: str
    type: IndexType
    definition_bson: bytes
    def __init__(self, request_id: _Optional[str] = ..., collection_id: _Optional[str] = ..., name: _Optional[str] = ..., type: _Optional[_Union[IndexType, str]] = ..., definition_bson: _Optional[bytes] = ...) -> None: ...

class UpdateIndexRequest(_message.Message):
    __slots__ = ("request_id", "index_id", "expected_revision", "definition_bson")
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    DEFINITION_BSON_FIELD_NUMBER: _ClassVar[int]
    request_id: str
    index_id: str
    expected_revision: int
    definition_bson: bytes
    def __init__(self, request_id: _Optional[str] = ..., index_id: _Optional[str] = ..., expected_revision: _Optional[int] = ..., definition_bson: _Optional[bytes] = ...) -> None: ...

class DropIndexRequest(_message.Message):
    __slots__ = ("request_id", "index_id", "expected_revision")
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    EXPECTED_REVISION_FIELD_NUMBER: _ClassVar[int]
    request_id: str
    index_id: str
    expected_revision: int
    def __init__(self, request_id: _Optional[str] = ..., index_id: _Optional[str] = ..., expected_revision: _Optional[int] = ...) -> None: ...

class GetIndexRequest(_message.Message):
    __slots__ = ("index_id",)
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    def __init__(self, index_id: _Optional[str] = ...) -> None: ...

class ListIndexesRequest(_message.Message):
    __slots__ = ("collection_id", "page_size", "page_token")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    PAGE_SIZE_FIELD_NUMBER: _ClassVar[int]
    PAGE_TOKEN_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    page_size: int
    page_token: str
    def __init__(self, collection_id: _Optional[str] = ..., page_size: _Optional[int] = ..., page_token: _Optional[str] = ...) -> None: ...

class ListIndexesResponse(_message.Message):
    __slots__ = ("indexes", "next_page_token")
    INDEXES_FIELD_NUMBER: _ClassVar[int]
    NEXT_PAGE_TOKEN_FIELD_NUMBER: _ClassVar[int]
    indexes: _containers.RepeatedCompositeFieldContainer[IndexDescription]
    next_page_token: str
    def __init__(self, indexes: _Optional[_Iterable[_Union[IndexDescription, _Mapping]]] = ..., next_page_token: _Optional[str] = ...) -> None: ...

class IndexDescription(_message.Message):
    __slots__ = ("index_id", "collection_id", "name", "type", "desired_revision", "desired_definition_bson", "lifecycle_state", "live", "building", "failure_message", "active_revision")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    DESIRED_REVISION_FIELD_NUMBER: _ClassVar[int]
    DESIRED_DEFINITION_BSON_FIELD_NUMBER: _ClassVar[int]
    LIFECYCLE_STATE_FIELD_NUMBER: _ClassVar[int]
    LIVE_FIELD_NUMBER: _ClassVar[int]
    BUILDING_FIELD_NUMBER: _ClassVar[int]
    FAILURE_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_REVISION_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    collection_id: str
    name: str
    type: IndexType
    desired_revision: int
    desired_definition_bson: bytes
    lifecycle_state: str
    live: IndexWatermarks
    building: IndexWatermarks
    failure_message: str
    active_revision: int
    def __init__(self, index_id: _Optional[str] = ..., collection_id: _Optional[str] = ..., name: _Optional[str] = ..., type: _Optional[_Union[IndexType, str]] = ..., desired_revision: _Optional[int] = ..., desired_definition_bson: _Optional[bytes] = ..., lifecycle_state: _Optional[str] = ..., live: _Optional[_Union[IndexWatermarks, _Mapping]] = ..., building: _Optional[_Union[IndexWatermarks, _Mapping]] = ..., failure_message: _Optional[str] = ..., active_revision: _Optional[int] = ...) -> None: ...

class Mutation(_message.Message):
    __slots__ = ("type", "bson")
    TYPE_FIELD_NUMBER: _ClassVar[int]
    BSON_FIELD_NUMBER: _ClassVar[int]
    type: MutationType
    bson: bytes
    def __init__(self, type: _Optional[_Union[MutationType, str]] = ..., bson: _Optional[bytes] = ...) -> None: ...

class WriteRequest(_message.Message):
    __slots__ = ("collection_id", "request_id", "mutation")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    MUTATION_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    mutation: Mutation
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ..., mutation: _Optional[_Union[Mutation, _Mapping]] = ...) -> None: ...

class WriteBatchRequest(_message.Message):
    __slots__ = ("collection_id", "request_id", "mutations")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    MUTATIONS_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    mutations: _containers.RepeatedCompositeFieldContainer[Mutation]
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ..., mutations: _Optional[_Iterable[_Union[Mutation, _Mapping]]] = ...) -> None: ...

class MutationResult(_message.Message):
    __slots__ = ("ordinal", "status_code", "message", "accepted_sequence", "state", "source_existed")
    ORDINAL_FIELD_NUMBER: _ClassVar[int]
    STATUS_CODE_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ACCEPTED_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_EXISTED_FIELD_NUMBER: _ClassVar[int]
    ordinal: int
    status_code: int
    message: str
    accepted_sequence: int
    state: WriteItemState
    source_existed: bool
    def __init__(self, ordinal: _Optional[int] = ..., status_code: _Optional[int] = ..., message: _Optional[str] = ..., accepted_sequence: _Optional[int] = ..., state: _Optional[_Union[WriteItemState, str]] = ..., source_existed: _Optional[bool] = ...) -> None: ...

class WriteResponse(_message.Message):
    __slots__ = ("collection_id", "request_id", "results", "complete")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    RESULTS_FIELD_NUMBER: _ClassVar[int]
    COMPLETE_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    results: _containers.RepeatedCompositeFieldContainer[MutationResult]
    complete: bool
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ..., results: _Optional[_Iterable[_Union[MutationResult, _Mapping]]] = ..., complete: _Optional[bool] = ...) -> None: ...

class GetWriteResultRequest(_message.Message):
    __slots__ = ("collection_id", "request_id")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ...) -> None: ...

class AwaitSequenceRequest(_message.Message):
    __slots__ = ("index_id", "generation_id", "sequence", "condition")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    CONDITION_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    generation_id: str
    sequence: int
    condition: SequenceCondition
    def __init__(self, index_id: _Optional[str] = ..., generation_id: _Optional[str] = ..., sequence: _Optional[int] = ..., condition: _Optional[_Union[SequenceCondition, str]] = ...) -> None: ...

class IndexWatermarks(_message.Message):
    __slots__ = ("generation_id", "applied_sequence", "committed_sequence", "visible_sequence", "snapshot_documents", "snapshot_complete")
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    APPLIED_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    COMMITTED_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    VISIBLE_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_DOCUMENTS_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_COMPLETE_FIELD_NUMBER: _ClassVar[int]
    generation_id: str
    applied_sequence: int
    committed_sequence: int
    visible_sequence: int
    snapshot_documents: int
    snapshot_complete: bool
    def __init__(self, generation_id: _Optional[str] = ..., applied_sequence: _Optional[int] = ..., committed_sequence: _Optional[int] = ..., visible_sequence: _Optional[int] = ..., snapshot_documents: _Optional[int] = ..., snapshot_complete: _Optional[bool] = ...) -> None: ...

class DocumentId(_message.Message):
    __slots__ = ("string_value", "object_id", "int32_value", "int64_value")
    STRING_VALUE_FIELD_NUMBER: _ClassVar[int]
    OBJECT_ID_FIELD_NUMBER: _ClassVar[int]
    INT32_VALUE_FIELD_NUMBER: _ClassVar[int]
    INT64_VALUE_FIELD_NUMBER: _ClassVar[int]
    string_value: str
    object_id: str
    int32_value: int
    int64_value: int
    def __init__(self, string_value: _Optional[str] = ..., object_id: _Optional[str] = ..., int32_value: _Optional[int] = ..., int64_value: _Optional[int] = ...) -> None: ...

class UpsertRequest(_message.Message):
    __slots__ = ("collection_id", "request_id", "documents_bson", "concern")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    DOCUMENTS_BSON_FIELD_NUMBER: _ClassVar[int]
    CONCERN_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    documents_bson: _containers.RepeatedScalarFieldContainer[bytes]
    concern: DocumentWriteConcern
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ..., documents_bson: _Optional[_Iterable[bytes]] = ..., concern: _Optional[_Union[DocumentWriteConcern, str]] = ...) -> None: ...

class UpsertResponse(_message.Message):
    __slots__ = ("collection_id", "request_id", "upserted_count", "accepted_sequence")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    UPSERTED_COUNT_FIELD_NUMBER: _ClassVar[int]
    ACCEPTED_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    upserted_count: int
    accepted_sequence: int
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ..., upserted_count: _Optional[int] = ..., accepted_sequence: _Optional[int] = ...) -> None: ...

class DeleteRequest(_message.Message):
    __slots__ = ("collection_id", "request_id", "ids", "concern")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    IDS_FIELD_NUMBER: _ClassVar[int]
    CONCERN_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    ids: _containers.RepeatedCompositeFieldContainer[DocumentId]
    concern: DocumentWriteConcern
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ..., ids: _Optional[_Iterable[_Union[DocumentId, _Mapping]]] = ..., concern: _Optional[_Union[DocumentWriteConcern, str]] = ...) -> None: ...

class DeleteResponse(_message.Message):
    __slots__ = ("collection_id", "request_id", "deleted_count", "accepted_sequence")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    DELETED_COUNT_FIELD_NUMBER: _ClassVar[int]
    ACCEPTED_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    deleted_count: int
    accepted_sequence: int
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ..., deleted_count: _Optional[int] = ..., accepted_sequence: _Optional[int] = ...) -> None: ...

class DeleteByQueryRequest(_message.Message):
    __slots__ = ("collection_id", "request_id", "index_id", "query_bson", "batch_size", "concern", "generation_id")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    QUERY_BSON_FIELD_NUMBER: _ClassVar[int]
    BATCH_SIZE_FIELD_NUMBER: _ClassVar[int]
    CONCERN_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    index_id: str
    query_bson: bytes
    batch_size: int
    concern: DocumentWriteConcern
    generation_id: str
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ..., index_id: _Optional[str] = ..., query_bson: _Optional[bytes] = ..., batch_size: _Optional[int] = ..., concern: _Optional[_Union[DocumentWriteConcern, str]] = ..., generation_id: _Optional[str] = ...) -> None: ...

class GetDeleteByQueryResultRequest(_message.Message):
    __slots__ = ("collection_id", "request_id")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ...) -> None: ...

class DeleteByQueryResult(_message.Message):
    __slots__ = ("collection_id", "request_id", "index_id", "generation_id", "snapshot_sequence", "selection_complete", "matched_count", "deleted_count", "conflict_count", "missing_count", "failed_count", "accepted_sequence", "complete", "failure_message")
    COLLECTION_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    SELECTION_COMPLETE_FIELD_NUMBER: _ClassVar[int]
    MATCHED_COUNT_FIELD_NUMBER: _ClassVar[int]
    DELETED_COUNT_FIELD_NUMBER: _ClassVar[int]
    CONFLICT_COUNT_FIELD_NUMBER: _ClassVar[int]
    MISSING_COUNT_FIELD_NUMBER: _ClassVar[int]
    FAILED_COUNT_FIELD_NUMBER: _ClassVar[int]
    ACCEPTED_SEQUENCE_FIELD_NUMBER: _ClassVar[int]
    COMPLETE_FIELD_NUMBER: _ClassVar[int]
    FAILURE_MESSAGE_FIELD_NUMBER: _ClassVar[int]
    collection_id: str
    request_id: str
    index_id: str
    generation_id: str
    snapshot_sequence: int
    selection_complete: bool
    matched_count: int
    deleted_count: int
    conflict_count: int
    missing_count: int
    failed_count: int
    accepted_sequence: int
    complete: bool
    failure_message: str
    def __init__(self, collection_id: _Optional[str] = ..., request_id: _Optional[str] = ..., index_id: _Optional[str] = ..., generation_id: _Optional[str] = ..., snapshot_sequence: _Optional[int] = ..., selection_complete: _Optional[bool] = ..., matched_count: _Optional[int] = ..., deleted_count: _Optional[int] = ..., conflict_count: _Optional[int] = ..., missing_count: _Optional[int] = ..., failed_count: _Optional[int] = ..., accepted_sequence: _Optional[int] = ..., complete: _Optional[bool] = ..., failure_message: _Optional[str] = ...) -> None: ...
