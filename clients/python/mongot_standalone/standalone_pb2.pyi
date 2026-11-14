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

class MeasurementMethod(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MEASUREMENT_METHOD_UNAVAILABLE: _ClassVar[MeasurementMethod]
    MEASUREMENT_METHOD_FILE_RANGE: _ClassVar[MeasurementMethod]
    MEASUREMENT_METHOD_RESOURCE_ACCOUNTING: _ClassVar[MeasurementMethod]
    MEASUREMENT_METHOD_CODEC_WORKING_SET: _ClassVar[MeasurementMethod]
    MEASUREMENT_METHOD_MODEL_ESTIMATE: _ClassVar[MeasurementMethod]

class MeasurementCompleteness(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    MEASUREMENT_COMPLETENESS_UNAVAILABLE: _ClassVar[MeasurementCompleteness]
    MEASUREMENT_COMPLETENESS_COMPLETE: _ClassVar[MeasurementCompleteness]
    MEASUREMENT_COMPLETENESS_PARTIAL: _ClassVar[MeasurementCompleteness]

class StatsCollectionState(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    STATS_COLLECTION_STATE_COLLECTING: _ClassVar[StatsCollectionState]
    STATS_COLLECTION_STATE_READY: _ClassVar[StatsCollectionState]

class DocumentWriteConcern(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DOCUMENT_WRITE_CONCERN_VISIBLE: _ClassVar[DocumentWriteConcern]
    DOCUMENT_WRITE_CONCERN_INDEXED: _ClassVar[DocumentWriteConcern]
    DOCUMENT_WRITE_CONCERN_COMMITTED: _ClassVar[DocumentWriteConcern]
INDEX_TYPE_UNSPECIFIED: IndexType
INDEX_TYPE_SEARCH: IndexType
INDEX_TYPE_VECTOR_SEARCH: IndexType
MEASUREMENT_METHOD_UNAVAILABLE: MeasurementMethod
MEASUREMENT_METHOD_FILE_RANGE: MeasurementMethod
MEASUREMENT_METHOD_RESOURCE_ACCOUNTING: MeasurementMethod
MEASUREMENT_METHOD_CODEC_WORKING_SET: MeasurementMethod
MEASUREMENT_METHOD_MODEL_ESTIMATE: MeasurementMethod
MEASUREMENT_COMPLETENESS_UNAVAILABLE: MeasurementCompleteness
MEASUREMENT_COMPLETENESS_COMPLETE: MeasurementCompleteness
MEASUREMENT_COMPLETENESS_PARTIAL: MeasurementCompleteness
STATS_COLLECTION_STATE_COLLECTING: StatsCollectionState
STATS_COLLECTION_STATE_READY: StatsCollectionState
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

class GetIndexStatsRequest(_message.Message):
    __slots__ = ("index_id", "generation_id", "refresh")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    REFRESH_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    generation_id: str
    refresh: bool
    def __init__(self, index_id: _Optional[str] = ..., generation_id: _Optional[str] = ..., refresh: _Optional[bool] = ...) -> None: ...

class ByteMeasurement(_message.Message):
    __slots__ = ("value", "method", "completeness", "included_components", "omitted_components")
    VALUE_FIELD_NUMBER: _ClassVar[int]
    METHOD_FIELD_NUMBER: _ClassVar[int]
    COMPLETENESS_FIELD_NUMBER: _ClassVar[int]
    INCLUDED_COMPONENTS_FIELD_NUMBER: _ClassVar[int]
    OMITTED_COMPONENTS_FIELD_NUMBER: _ClassVar[int]
    value: int
    method: MeasurementMethod
    completeness: MeasurementCompleteness
    included_components: _containers.RepeatedScalarFieldContainer[str]
    omitted_components: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, value: _Optional[int] = ..., method: _Optional[_Union[MeasurementMethod, str]] = ..., completeness: _Optional[_Union[MeasurementCompleteness, str]] = ..., included_components: _Optional[_Iterable[str]] = ..., omitted_components: _Optional[_Iterable[str]] = ...) -> None: ...

class IndexMemoryStats(_message.Message):
    __slots__ = ("accounted_reader_heap_bytes", "accounted_writer_buffer_heap_bytes", "accounted_cache_heap_bytes", "desired_vector_off_heap_bytes")
    ACCOUNTED_READER_HEAP_BYTES_FIELD_NUMBER: _ClassVar[int]
    ACCOUNTED_WRITER_BUFFER_HEAP_BYTES_FIELD_NUMBER: _ClassVar[int]
    ACCOUNTED_CACHE_HEAP_BYTES_FIELD_NUMBER: _ClassVar[int]
    DESIRED_VECTOR_OFF_HEAP_BYTES_FIELD_NUMBER: _ClassVar[int]
    accounted_reader_heap_bytes: ByteMeasurement
    accounted_writer_buffer_heap_bytes: ByteMeasurement
    accounted_cache_heap_bytes: ByteMeasurement
    desired_vector_off_heap_bytes: ByteMeasurement
    def __init__(self, accounted_reader_heap_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., accounted_writer_buffer_heap_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., accounted_cache_heap_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., desired_vector_off_heap_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ...) -> None: ...

class IndexStats(_message.Message):
    __slots__ = ("index_id", "generation_id", "active_revision", "snapshot_id", "sampled_at_unix_millis", "document_count", "lucene_document_count", "deleted_lucene_document_count", "index_size_bytes", "directory_size_bytes", "directory_sampled_at_unix_millis", "average_index_bytes_per_document", "average_directory_bytes_per_document", "memory", "memory_sampled_at_unix_millis", "age_millis")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_REVISION_FIELD_NUMBER: _ClassVar[int]
    SNAPSHOT_ID_FIELD_NUMBER: _ClassVar[int]
    SAMPLED_AT_UNIX_MILLIS_FIELD_NUMBER: _ClassVar[int]
    DOCUMENT_COUNT_FIELD_NUMBER: _ClassVar[int]
    LUCENE_DOCUMENT_COUNT_FIELD_NUMBER: _ClassVar[int]
    DELETED_LUCENE_DOCUMENT_COUNT_FIELD_NUMBER: _ClassVar[int]
    INDEX_SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    DIRECTORY_SIZE_BYTES_FIELD_NUMBER: _ClassVar[int]
    DIRECTORY_SAMPLED_AT_UNIX_MILLIS_FIELD_NUMBER: _ClassVar[int]
    AVERAGE_INDEX_BYTES_PER_DOCUMENT_FIELD_NUMBER: _ClassVar[int]
    AVERAGE_DIRECTORY_BYTES_PER_DOCUMENT_FIELD_NUMBER: _ClassVar[int]
    MEMORY_FIELD_NUMBER: _ClassVar[int]
    MEMORY_SAMPLED_AT_UNIX_MILLIS_FIELD_NUMBER: _ClassVar[int]
    AGE_MILLIS_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    generation_id: str
    active_revision: int
    snapshot_id: str
    sampled_at_unix_millis: int
    document_count: int
    lucene_document_count: int
    deleted_lucene_document_count: int
    index_size_bytes: int
    directory_size_bytes: ByteMeasurement
    directory_sampled_at_unix_millis: int
    average_index_bytes_per_document: float
    average_directory_bytes_per_document: float
    memory: IndexMemoryStats
    memory_sampled_at_unix_millis: int
    age_millis: int
    def __init__(self, index_id: _Optional[str] = ..., generation_id: _Optional[str] = ..., active_revision: _Optional[int] = ..., snapshot_id: _Optional[str] = ..., sampled_at_unix_millis: _Optional[int] = ..., document_count: _Optional[int] = ..., lucene_document_count: _Optional[int] = ..., deleted_lucene_document_count: _Optional[int] = ..., index_size_bytes: _Optional[int] = ..., directory_size_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., directory_sampled_at_unix_millis: _Optional[int] = ..., average_index_bytes_per_document: _Optional[float] = ..., average_directory_bytes_per_document: _Optional[float] = ..., memory: _Optional[_Union[IndexMemoryStats, _Mapping]] = ..., memory_sampled_at_unix_millis: _Optional[int] = ..., age_millis: _Optional[int] = ...) -> None: ...

class GetIndexFieldStatsRequest(_message.Message):
    __slots__ = ("index_id", "generation_id", "refresh", "paths", "page_size", "page_token")
    INDEX_ID_FIELD_NUMBER: _ClassVar[int]
    GENERATION_ID_FIELD_NUMBER: _ClassVar[int]
    REFRESH_FIELD_NUMBER: _ClassVar[int]
    PATHS_FIELD_NUMBER: _ClassVar[int]
    PAGE_SIZE_FIELD_NUMBER: _ClassVar[int]
    PAGE_TOKEN_FIELD_NUMBER: _ClassVar[int]
    index_id: str
    generation_id: str
    refresh: bool
    paths: _containers.RepeatedScalarFieldContainer[str]
    page_size: int
    page_token: str
    def __init__(self, index_id: _Optional[str] = ..., generation_id: _Optional[str] = ..., refresh: _Optional[bool] = ..., paths: _Optional[_Iterable[str]] = ..., page_size: _Optional[int] = ..., page_token: _Optional[str] = ...) -> None: ...

class FieldRepresentationStats(_message.Message):
    __slots__ = ("physical_name", "mapping_type", "multi", "embedded_root", "attributed_disk_bytes", "accounted_reader_heap_bytes", "desired_vector_off_heap_bytes", "vector_working_set_categories")
    class VectorWorkingSetCategoriesEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: int
        def __init__(self, key: _Optional[str] = ..., value: _Optional[int] = ...) -> None: ...
    PHYSICAL_NAME_FIELD_NUMBER: _ClassVar[int]
    MAPPING_TYPE_FIELD_NUMBER: _ClassVar[int]
    MULTI_FIELD_NUMBER: _ClassVar[int]
    EMBEDDED_ROOT_FIELD_NUMBER: _ClassVar[int]
    ATTRIBUTED_DISK_BYTES_FIELD_NUMBER: _ClassVar[int]
    ACCOUNTED_READER_HEAP_BYTES_FIELD_NUMBER: _ClassVar[int]
    DESIRED_VECTOR_OFF_HEAP_BYTES_FIELD_NUMBER: _ClassVar[int]
    VECTOR_WORKING_SET_CATEGORIES_FIELD_NUMBER: _ClassVar[int]
    physical_name: str
    mapping_type: str
    multi: str
    embedded_root: str
    attributed_disk_bytes: ByteMeasurement
    accounted_reader_heap_bytes: ByteMeasurement
    desired_vector_off_heap_bytes: ByteMeasurement
    vector_working_set_categories: _containers.ScalarMap[str, int]
    def __init__(self, physical_name: _Optional[str] = ..., mapping_type: _Optional[str] = ..., multi: _Optional[str] = ..., embedded_root: _Optional[str] = ..., attributed_disk_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., accounted_reader_heap_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., desired_vector_off_heap_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., vector_working_set_categories: _Optional[_Mapping[str, int]] = ...) -> None: ...

class IndexFieldStats(_message.Message):
    __slots__ = ("path", "configured", "observed", "attributed_disk_bytes", "accounted_reader_heap_bytes", "desired_vector_off_heap_bytes", "representations")
    PATH_FIELD_NUMBER: _ClassVar[int]
    CONFIGURED_FIELD_NUMBER: _ClassVar[int]
    OBSERVED_FIELD_NUMBER: _ClassVar[int]
    ATTRIBUTED_DISK_BYTES_FIELD_NUMBER: _ClassVar[int]
    ACCOUNTED_READER_HEAP_BYTES_FIELD_NUMBER: _ClassVar[int]
    DESIRED_VECTOR_OFF_HEAP_BYTES_FIELD_NUMBER: _ClassVar[int]
    REPRESENTATIONS_FIELD_NUMBER: _ClassVar[int]
    path: str
    configured: bool
    observed: bool
    attributed_disk_bytes: ByteMeasurement
    accounted_reader_heap_bytes: ByteMeasurement
    desired_vector_off_heap_bytes: ByteMeasurement
    representations: _containers.RepeatedCompositeFieldContainer[FieldRepresentationStats]
    def __init__(self, path: _Optional[str] = ..., configured: _Optional[bool] = ..., observed: _Optional[bool] = ..., attributed_disk_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., accounted_reader_heap_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., desired_vector_off_heap_bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., representations: _Optional[_Iterable[_Union[FieldRepresentationStats, _Mapping]]] = ...) -> None: ...

class StorageStatsBucket(_message.Message):
    __slots__ = ("component", "owners", "bytes", "unattributed", "reason", "owner_count", "owner_list_complete")
    COMPONENT_FIELD_NUMBER: _ClassVar[int]
    OWNERS_FIELD_NUMBER: _ClassVar[int]
    BYTES_FIELD_NUMBER: _ClassVar[int]
    UNATTRIBUTED_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    OWNER_COUNT_FIELD_NUMBER: _ClassVar[int]
    OWNER_LIST_COMPLETE_FIELD_NUMBER: _ClassVar[int]
    component: str
    owners: _containers.RepeatedScalarFieldContainer[str]
    bytes: int
    unattributed: bool
    reason: str
    owner_count: int
    owner_list_complete: bool
    def __init__(self, component: _Optional[str] = ..., owners: _Optional[_Iterable[str]] = ..., bytes: _Optional[int] = ..., unattributed: _Optional[bool] = ..., reason: _Optional[str] = ..., owner_count: _Optional[int] = ..., owner_list_complete: _Optional[bool] = ...) -> None: ...

class MemoryStatsBucket(_message.Message):
    __slots__ = ("component", "owners", "bytes", "owner_count", "owner_list_complete")
    COMPONENT_FIELD_NUMBER: _ClassVar[int]
    OWNERS_FIELD_NUMBER: _ClassVar[int]
    BYTES_FIELD_NUMBER: _ClassVar[int]
    OWNER_COUNT_FIELD_NUMBER: _ClassVar[int]
    OWNER_LIST_COMPLETE_FIELD_NUMBER: _ClassVar[int]
    component: str
    owners: _containers.RepeatedScalarFieldContainer[str]
    bytes: ByteMeasurement
    owner_count: int
    owner_list_complete: bool
    def __init__(self, component: _Optional[str] = ..., owners: _Optional[_Iterable[str]] = ..., bytes: _Optional[_Union[ByteMeasurement, _Mapping]] = ..., owner_count: _Optional[int] = ..., owner_list_complete: _Optional[bool] = ...) -> None: ...

class IndexFieldStatsResponse(_message.Message):
    __slots__ = ("state", "index_stats", "fields", "total_attributed_disk_bytes", "shared_disk_bytes", "unattributed_disk_bytes", "attribution_coverage", "storage_buckets", "memory_buckets", "next_page_token", "refresh_in_progress", "retry_after_millis")
    STATE_FIELD_NUMBER: _ClassVar[int]
    INDEX_STATS_FIELD_NUMBER: _ClassVar[int]
    FIELDS_FIELD_NUMBER: _ClassVar[int]
    TOTAL_ATTRIBUTED_DISK_BYTES_FIELD_NUMBER: _ClassVar[int]
    SHARED_DISK_BYTES_FIELD_NUMBER: _ClassVar[int]
    UNATTRIBUTED_DISK_BYTES_FIELD_NUMBER: _ClassVar[int]
    ATTRIBUTION_COVERAGE_FIELD_NUMBER: _ClassVar[int]
    STORAGE_BUCKETS_FIELD_NUMBER: _ClassVar[int]
    MEMORY_BUCKETS_FIELD_NUMBER: _ClassVar[int]
    NEXT_PAGE_TOKEN_FIELD_NUMBER: _ClassVar[int]
    REFRESH_IN_PROGRESS_FIELD_NUMBER: _ClassVar[int]
    RETRY_AFTER_MILLIS_FIELD_NUMBER: _ClassVar[int]
    state: StatsCollectionState
    index_stats: IndexStats
    fields: _containers.RepeatedCompositeFieldContainer[IndexFieldStats]
    total_attributed_disk_bytes: int
    shared_disk_bytes: int
    unattributed_disk_bytes: int
    attribution_coverage: float
    storage_buckets: _containers.RepeatedCompositeFieldContainer[StorageStatsBucket]
    memory_buckets: _containers.RepeatedCompositeFieldContainer[MemoryStatsBucket]
    next_page_token: str
    refresh_in_progress: bool
    retry_after_millis: int
    def __init__(self, state: _Optional[_Union[StatsCollectionState, str]] = ..., index_stats: _Optional[_Union[IndexStats, _Mapping]] = ..., fields: _Optional[_Iterable[_Union[IndexFieldStats, _Mapping]]] = ..., total_attributed_disk_bytes: _Optional[int] = ..., shared_disk_bytes: _Optional[int] = ..., unattributed_disk_bytes: _Optional[int] = ..., attribution_coverage: _Optional[float] = ..., storage_buckets: _Optional[_Iterable[_Union[StorageStatsBucket, _Mapping]]] = ..., memory_buckets: _Optional[_Iterable[_Union[MemoryStatsBucket, _Mapping]]] = ..., next_page_token: _Optional[str] = ..., refresh_in_progress: _Optional[bool] = ..., retry_after_millis: _Optional[int] = ...) -> None: ...

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
