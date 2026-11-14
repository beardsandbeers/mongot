package com.xgen.mongot.index.statistics;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** Engine statistics with explicit accounting domains and coverage, independent of transport. */
public record IndexStatistics(
    long sampledAtMillis,
    long documentCount,
    long luceneDocumentCount,
    long deletedLuceneDocumentCount,
    long indexSizeBytes,
    long directorySampledAtMillis,
    Bytes directorySize,
    long memorySampledAtMillis,
    Memory memory,
    List<Field> fields,
    List<StorageBucket> storageBuckets,
    List<MemoryBucket> memoryBuckets,
    long attributedDiskBytes,
    long sharedDiskBytes,
    long unattributedDiskBytes) {

  public enum Method {
    FILE_RANGE,
    RESOURCE_ACCOUNTING,
    CODEC_WORKING_SET,
    MODEL_ESTIMATE,
    UNAVAILABLE
  }

  public enum Completeness {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE
  }

  public record Bytes(
      OptionalLong value,
      Method method,
      Completeness completeness,
      List<String> includedComponents,
      List<String> omittedComponents) {
    public static Bytes unavailable(String reason) {
      return new Bytes(
          OptionalLong.empty(),
          Method.UNAVAILABLE,
          Completeness.UNAVAILABLE,
          List.of(),
          List.of(reason));
    }

    public static Bytes measured(
        long value, Method method, boolean complete, List<String> included, List<String> omitted) {
      if (value < 0) {
        throw new IllegalArgumentException("A byte measurement cannot be negative");
      }
      return new Bytes(
          OptionalLong.of(value),
          method,
          complete ? Completeness.COMPLETE : Completeness.PARTIAL,
          List.copyOf(included),
          List.copyOf(omitted));
    }
  }

  public record Memory(
      Bytes readerHeap, Bytes writerBufferHeap, Bytes cacheHeap, Bytes desiredVectorOffHeap) {}

  public record Representation(
      String physicalName,
      String mappingType,
      String multi,
      String embeddedRoot,
      Bytes disk,
      Bytes readerHeap,
      Bytes desiredVectorOffHeap,
      Map<String, Long> vectorCategories) {}

  public record Field(
      String path,
      boolean configured,
      boolean observed,
      Bytes disk,
      Bytes readerHeap,
      Bytes desiredVectorOffHeap,
      List<Representation> representations) {}

  /** Disjoint physical byte ranges aggregated by component and ownership. */
  public record StorageBucket(
      String component, List<String> owners, long bytes, boolean unattributed, String reason) {}

  public record MemoryBucket(String component, List<String> owners, Bytes bytes) {}
}
