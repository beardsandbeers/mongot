package com.xgen.mongot.index.lucene.merge;

import com.xgen.mongot.featureflag.Feature;
import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.index.lucene.config.LuceneConfig;
import com.xgen.mongot.monitor.Gate;
import com.xgen.mongot.util.Bytes;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.TieredMergePolicy;

/** Factory for creating Lucene {@link MergePolicy} instances. */
public final class MergePolicyFactory {

  private MergePolicyFactory() {}

  /**
   * Creates a {@link MergePolicy} configured from {@link LuceneConfig}, optionally wrapped in
   * {@link DiskUtilizationAwareMergePolicy}.
   */
  public static MergePolicy createMergePolicy(
      LuceneConfig config, Gate mergeGate, MeterRegistry meterRegistry) {
    return maybeWrapDiskAware(createTieredMergePolicy(config), config, mergeGate, meterRegistry);
  }

  /**
   * Creates a {@link MergePolicy} from a {@link TieredMergePolicy}, optionally wrapped in {@link
   * DiskUtilizationAwareMergePolicy}.
   */
  public static MergePolicy createMergePolicy(
      TieredMergePolicy tieredMergePolicy,
      LuceneConfig config,
      Gate mergeGate,
      MeterRegistry meterRegistry) {
    return maybeWrapDiskAware(tieredMergePolicy, config, mergeGate, meterRegistry);
  }

  /** Creates a vector merge policy if vector config is present. */
  public static Optional<MergePolicy> createVectorMergePolicy(
      LuceneConfig config, FeatureFlags featureFlags, Gate mergeGate, MeterRegistry meterRegistry) {
    return createVectorMergePolicy(
        createTieredMergePolicy(config), config, featureFlags, mergeGate, meterRegistry);
  }

  /** Creates a vector merge policy from a {@link TieredMergePolicy} if vector config is present. */
  public static Optional<MergePolicy> createVectorMergePolicy(
      TieredMergePolicy tieredMergePolicy,
      LuceneConfig config,
      FeatureFlags featureFlags,
      Gate mergeGate,
      MeterRegistry meterRegistry) {
    if (config.vectorMergePolicyConfig().isEmpty()) {
      return Optional.empty();
    }
    if (featureFlags.isEnabled(Feature.FLOOR_SEGMENT_MB)) {
      config.floorSegmentMB().ifPresent(tieredMergePolicy::setFloorSegmentMB);
    }
    MergePolicy mergePolicy =
        maybeWrapDiskAware(tieredMergePolicy, config, mergeGate, meterRegistry);

    return config
        .vectorMergePolicyConfig()
        .map(
            (vectorConfig) ->
                VectorMergePolicy.newBuilder()
                    .setMaxCompoundDataBytes(
                        Bytes.ofMebi(vectorConfig.maxCompoundDataMb()).toBytes())
                    .setMaxVectorInputBytes(Bytes.ofMebi(vectorConfig.maxVectorInputMb()).toBytes())
                    .setMergeBudgetBytes(Bytes.ofMebi(vectorConfig.mergeBudgetMb()).toBytes())
                    .setSegmentHeapBytesBudget(
                        Bytes.ofMebi(vectorConfig.segmentHeapBudgetMb()).toBytes())
                    .setGlobalHeapBytesBudget(
                        Bytes.ofMebi(vectorConfig.globalHeapBudgetMb()).toBytes())
                    .build(mergePolicy, meterRegistry));
  }

  /** Creates a {@link TieredMergePolicy} configured from {@link LuceneConfig}. */
  public static TieredMergePolicy createTieredMergePolicy(LuceneConfig config) {
    TieredMergePolicy tieredMergePolicy = new TieredMergePolicy();
    tieredMergePolicy.setMaxMergedSegmentMB(config.maxMergedSegmentSize().toMebi());
    config.deletesPctAllowed().ifPresent(tieredMergePolicy::setDeletesPctAllowed);
    config
        .forceMergeDeletesPctAllowed()
        .ifPresent(tieredMergePolicy::setForceMergeDeletesPctAllowed);

    return tieredMergePolicy;
  }

  private static MergePolicy maybeWrapDiskAware(
      MergePolicy mergePolicy, LuceneConfig config, Gate mergeGate, MeterRegistry meterRegistry) {
    if (config.mergePolicyDiskUtilizationConfig().isPresent()) {
      return new DiskUtilizationAwareMergePolicy(mergePolicy, mergeGate, meterRegistry);
    }
    return mergePolicy;
  }
}
