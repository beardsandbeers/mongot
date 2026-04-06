package com.xgen.mongot.replication.mongodb.initialsync;

import org.junit.Assert;
import org.junit.Test;

public class BufferlessInitialSyncManagerTest {

  @Test
  public void testGetSizeBucketLessThan100MB() {
    Assert.assertEquals("lt_100MB", BufferlessInitialSyncManager.getSizeBucket(0));
    Assert.assertEquals("lt_100MB", BufferlessInitialSyncManager.getSizeBucket(1));
    Assert.assertEquals(
        "lt_100MB", BufferlessInitialSyncManager.getSizeBucket(100L * 1024 * 1024 - 1));
  }

  @Test
  public void testGetSizeBucket100MBto1GB() {
    Assert.assertEquals(
        "100MB_1GB", BufferlessInitialSyncManager.getSizeBucket(100L * 1024 * 1024));
    Assert.assertEquals(
        "100MB_1GB", BufferlessInitialSyncManager.getSizeBucket(1024L * 1024 * 1024 - 1));
  }

  @Test
  public void testGetSizeBucketAbove1GB() {
    Assert.assertEquals("gt_1GB", BufferlessInitialSyncManager.getSizeBucket(1024L * 1024 * 1024));
    Assert.assertEquals(
        "gt_1GB", BufferlessInitialSyncManager.getSizeBucket(10L * 1024 * 1024 * 1024));
  }
}
