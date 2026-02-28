package com.xgen.mongot.index.lucene;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xgen.mongot.index.lucene.merge.DiskUtilizationAwareMergePolicy;
import com.xgen.mongot.monitor.ToggleGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileStore;
import org.apache.lucene.index.MergePolicy;
import org.junit.Assert;
import org.junit.Test;

public class DiskUtilizationAwareMergePolicyTest {
  private final MergePolicy mockMergePolicy;
  private final SimpleMeterRegistry meterRegistry;
  private final MergePolicy.MergeSpecification mergeSpecification;

  public DiskUtilizationAwareMergePolicyTest() {
    this.mockMergePolicy = mock(MergePolicy.class);
    this.meterRegistry = new SimpleMeterRegistry();

    this.mergeSpecification = new MergePolicy.MergeSpecification();
    this.mergeSpecification.add(mock(MergePolicy.OneMerge.class));
  }

  @Test
  public void testAllowMerge() throws IOException {
    // Create a mock FileStore with 50% disk utilization
    var mockFileStore = mock(FileStore.class);
    when(mockFileStore.getTotalSpace()).thenReturn(1000_000_000L);
    when(mockFileStore.getUsableSpace()).thenReturn(500_000_000L);

    var mergePolicy =
        new DiskUtilizationAwareMergePolicy(
            this.mockMergePolicy, ToggleGate.opened(), this.meterRegistry);
    Assert.assertEquals(
        this.mergeSpecification, mergePolicy.maybePruneMergeSpecification(this.mergeSpecification));
  }

  @Test
  public void testBlockMerge() throws IOException {
    // Create a mock FileStore with 85% disk utilization (above close threshold)
    var mockFileStore = mock(FileStore.class);
    when(mockFileStore.getTotalSpace()).thenReturn(1000_000_000L);
    when(mockFileStore.getUsableSpace()).thenReturn(150_000_000L);

    var mergePolicy =
        new DiskUtilizationAwareMergePolicy(
            this.mockMergePolicy, ToggleGate.closed(), this.meterRegistry);
    Assert.assertNull(mergePolicy.maybePruneMergeSpecification(this.mergeSpecification));
    Assert.assertEquals(
        1,
        this.meterRegistry
            .find("diskUtilizationAwarenessMergePolicy.discardedMerge")
            .counter()
            .count(),
        0.001);
  }
}
