package com.xgen.mongot.standalone.runtime;

import com.xgen.mongot.index.statistics.IndexStatistics;
import com.xgen.mongot.standalone.grpc.ByteMeasurement;
import com.xgen.mongot.standalone.grpc.FieldRepresentationStats;
import com.xgen.mongot.standalone.grpc.IndexFieldStats;
import com.xgen.mongot.standalone.grpc.IndexFieldStatsResponse;
import com.xgen.mongot.standalone.grpc.IndexMemoryStats;
import com.xgen.mongot.standalone.grpc.IndexStats;
import com.xgen.mongot.standalone.grpc.MeasurementCompleteness;
import com.xgen.mongot.standalone.grpc.MeasurementMethod;
import com.xgen.mongot.standalone.grpc.MemoryStatsBucket;
import com.xgen.mongot.standalone.grpc.StatsCollectionState;
import com.xgen.mongot.standalone.grpc.StorageStatsBucket;
import java.util.List;
import java.util.Set;

/** Transport conversion preserves missing values and never flattens independent memory domains. */
final class StatisticsProtoMapper {
  private static final int MAX_BUCKET_OWNERS = 128;

  private StatisticsProtoMapper() {}

  static ByteMeasurement bytes(IndexStatistics.Bytes bytes) {
    var result =
        ByteMeasurement.newBuilder()
            .setMethod(MeasurementMethod.valueOf("MEASUREMENT_METHOD_" + bytes.method().name()))
            .setCompleteness(
                MeasurementCompleteness.valueOf(
                    "MEASUREMENT_COMPLETENESS_" + bytes.completeness().name()))
            .addAllIncludedComponents(bytes.includedComponents())
            .addAllOmittedComponents(bytes.omittedComponents());
    bytes.value().ifPresent(result::setValue);
    return result.build();
  }

  static IndexStats summary(
      IndexStatistics value, String index, String generation, long revision, String snapshot) {
    var memory = value.memory();
    var result =
        IndexStats.newBuilder()
            .setIndexId(index)
            .setGenerationId(generation)
            .setActiveRevision(revision)
            .setSnapshotId(snapshot)
            .setSampledAtUnixMillis(value.sampledAtMillis())
            .setDocumentCount(value.documentCount())
            .setLuceneDocumentCount(value.luceneDocumentCount())
            .setDeletedLuceneDocumentCount(value.deletedLuceneDocumentCount())
            .setIndexSizeBytes(value.indexSizeBytes())
            .setDirectorySizeBytes(bytes(value.directorySize()))
            .setDirectorySampledAtUnixMillis(value.directorySampledAtMillis())
            .setMemorySampledAtUnixMillis(value.memorySampledAtMillis())
            .setMemory(
                IndexMemoryStats.newBuilder()
                    .setAccountedReaderHeapBytes(bytes(memory.readerHeap()))
                    .setAccountedWriterBufferHeapBytes(bytes(memory.writerBufferHeap()))
                    .setAccountedCacheHeapBytes(bytes(memory.cacheHeap()))
                    .setDesiredVectorOffHeapBytes(bytes(memory.desiredVectorOffHeap())));
    if (value.documentCount() > 0) {
      result.setAverageIndexBytesPerDocument(
          (double) value.indexSizeBytes() / value.documentCount());
      value
          .directorySize()
          .value()
          .ifPresent(
              size ->
                  result.setAverageDirectoryBytesPerDocument(
                      (double) size / value.documentCount()));
    }
    return result.build();
  }

  static IndexFieldStatsResponse fields(
      IndexStatistics value, IndexStats summary, List<String> paths) {
    Set<String> selected = Set.copyOf(paths);
    var result =
        IndexFieldStatsResponse.newBuilder()
            .setState(StatsCollectionState.STATS_COLLECTION_STATE_READY)
            .setIndexStats(summary)
            .setTotalAttributedDiskBytes(value.attributedDiskBytes())
            .setSharedDiskBytes(value.sharedDiskBytes())
            .setUnattributedDiskBytes(value.unattributedDiskBytes());
    if (value.indexSizeBytes() > 0) {
      result.setAttributionCoverage((double) value.attributedDiskBytes() / value.indexSizeBytes());
    }
    for (var field : value.fields()) {
      if (!selected.isEmpty() && !selected.contains(field.path())) {
        continue;
      }
      var converted =
          IndexFieldStats.newBuilder()
              .setPath(field.path())
              .setConfigured(field.configured())
              .setObserved(field.observed())
              .setAttributedDiskBytes(bytes(field.disk()))
              .setAccountedReaderHeapBytes(bytes(field.readerHeap()))
              .setDesiredVectorOffHeapBytes(bytes(field.desiredVectorOffHeap()));
      for (var representation : field.representations()) {
        converted.addRepresentations(
            FieldRepresentationStats.newBuilder()
                .setPhysicalName(representation.physicalName())
                .setMappingType(representation.mappingType())
                .setMulti(representation.multi())
                .setEmbeddedRoot(representation.embeddedRoot())
                .setAttributedDiskBytes(bytes(representation.disk()))
                .setAccountedReaderHeapBytes(bytes(representation.readerHeap()))
                .setDesiredVectorOffHeapBytes(bytes(representation.desiredVectorOffHeap()))
                .putAllVectorWorkingSetCategories(representation.vectorCategories()));
      }
      result.addFields(converted);
    }
    for (var bucket : value.storageBuckets()) {
      result.addStorageBuckets(
          StorageStatsBucket.newBuilder()
              .setComponent(bucket.component())
              .setBytes(bucket.bytes())
              .setUnattributed(bucket.unattributed())
              .setReason(bucket.reason())
              .addAllOwners(bucket.owners().stream().limit(MAX_BUCKET_OWNERS).toList())
              .setOwnerCount(bucket.owners().size())
              .setOwnerListComplete(bucket.owners().size() <= MAX_BUCKET_OWNERS));
    }
    for (var bucket : value.memoryBuckets()) {
      result.addMemoryBuckets(
          MemoryStatsBucket.newBuilder()
              .setComponent(bucket.component())
              .setBytes(bytes(bucket.bytes()))
              .addAllOwners(bucket.owners().stream().limit(MAX_BUCKET_OWNERS).toList())
              .setOwnerCount(bucket.owners().size())
              .setOwnerListComplete(bucket.owners().size() <= MAX_BUCKET_OWNERS));
    }
    return result.build();
  }
}
