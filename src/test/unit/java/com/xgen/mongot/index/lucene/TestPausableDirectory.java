package com.xgen.mongot.index.lucene;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.xgen.mongot.index.lucene.merge.PausableDirectory;
import com.xgen.mongot.monitor.Gate;
import com.xgen.mongot.monitor.ToggleGate;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.junit.Test;

/** Unit tests for {@link PausableDirectory}. */
public class TestPausableDirectory {

  /**
   * Test that PausableDirectory allows normal writes when gate is open (disk usage is low).
   */
  @Test
  public void testWriteBytes_gateOpen_writesImmediately() throws IOException {
    Gate openGate = ToggleGate.opened();
    Directory baseDir = new ByteBuffersDirectory();
    PausableDirectory pausableDir = new PausableDirectory(baseDir, openGate);

    IndexOutput output = pausableDir.createOutput("test.dat", IOContext.DEFAULT);

    long bytesToWrite = PausableDirectory.CHECK_PAUSE_INTERVAL_BYTES * 2;
    byte[] largeData = new byte[(int) bytesToWrite];
    for (int i = 0; i < largeData.length; i++) {
      largeData[i] = (byte) (i % 256);
    }

    output.writeBytes(largeData, 0, largeData.length);
    output.close();

    assertEquals(bytesToWrite, baseDir.fileLength("test.dat"));
    pausableDir.close();
  }

  /**
   * Test that PausableDirectory blocks writes when gate is closed, then resumes when gate opens.
   */
  @Test
  public void testWriteBytes_gateClosed_blocksUntilOpen() throws Exception {
    ToggleGate gate = ToggleGate.closed();
    Directory baseDir = new ByteBuffersDirectory();
    PausableDirectory pausableDir = new PausableDirectory(baseDir, gate);

    IndexOutput output = pausableDir.createOutput("test.dat", IOContext.DEFAULT);

    AtomicBoolean writeCompleted = new AtomicBoolean(false);
    AtomicReference<Exception> writeException = new AtomicReference<>();
    CountDownLatch writeStarted = new CountDownLatch(1);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(
          () -> {
            try {
              byte[] largeData = new byte[(int) PausableDirectory.CHECK_PAUSE_INTERVAL_BYTES];
              writeStarted.countDown();
              output.writeBytes(largeData, 0, largeData.length);
              writeCompleted.set(true);
            } catch (Exception e) {
              writeException.set(e);
            }
          });

      assertTrue("Write should have started", writeStarted.await(5, TimeUnit.SECONDS));
      Thread.sleep(100);
      assertTrue("Write should be blocked when gate is closed", !writeCompleted.get());

      gate.open();

      executor.shutdown();
      assertTrue(
          "Write should complete after gate opens",
          executor.awaitTermination(5, TimeUnit.SECONDS));

      if (writeException.get() != null) {
        throw writeException.get();
      }
      assertTrue("Write should have completed", writeCompleted.get());
    } finally {
      gate.open();
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    output.close();
    pausableDir.close();
  }

  /**
   * Test that PausableDirectory throws IOException when thread is interrupted while waiting.
   */
  @Test
  public void testWriteBytes_interruptedWhileWaiting_throwsIoException() throws Exception {
    ToggleGate gate = ToggleGate.closed();
    Directory baseDir = new ByteBuffersDirectory();
    PausableDirectory pausableDir = new PausableDirectory(baseDir, gate);

    IndexOutput output = pausableDir.createOutput("test.dat", IOContext.DEFAULT);

    AtomicReference<Exception> caughtException = new AtomicReference<>();
    CountDownLatch writeStarted = new CountDownLatch(1);

    Thread writeThread =
        new Thread(
            () -> {
              try {
                byte[] largeData = new byte[(int) PausableDirectory.CHECK_PAUSE_INTERVAL_BYTES];
                writeStarted.countDown();
                output.writeBytes(largeData, 0, largeData.length);
              } catch (Exception e) {
                caughtException.set(e);
              }
            });
    writeThread.start();

    assertTrue("Write should have started", writeStarted.await(5, TimeUnit.SECONDS));
    Thread.sleep(100);

    writeThread.interrupt();
    writeThread.join(5000);

    assertTrue("Should have caught an exception", caughtException.get() != null);
    assertTrue(
        "Exception should be IOException",
        caughtException.get() instanceof IOException);
    assertTrue(
        "Exception message should mention interruption",
        caughtException.get().getMessage().contains("Interrupted"));

    output.close();
    pausableDir.close();
  }

  /**
   * Test that PausableDirectory only checks gate status every CHECK_PAUSE_INTERVAL_BYTES.
   */
  @Test
  public void testCheckInterval_onlyChecksEvery256KB() throws IOException {
    ToggleGate gate = ToggleGate.opened();
    Directory baseDir = new ByteBuffersDirectory();
    PausableDirectory pausableDir = new PausableDirectory(baseDir, gate);

    IndexOutput output = pausableDir.createOutput("test.dat", IOContext.DEFAULT);

    int smallWriteSize = (int) (PausableDirectory.CHECK_PAUSE_INTERVAL_BYTES / 2);
    byte[] smallData = new byte[smallWriteSize];
    output.writeBytes(smallData, 0, smallData.length);

    gate.close();

    int remainingBytes = (int) (PausableDirectory.CHECK_PAUSE_INTERVAL_BYTES / 4);
    byte[] moreData = new byte[remainingBytes];
    output.writeBytes(moreData, 0, moreData.length);

    assertEquals(smallWriteSize + remainingBytes, output.getFilePointer());

    output.close();
    pausableDir.close();
  }

  /**
   * Test that PausableDirectory works with createTempOutput as well.
   */
  @Test
  public void testPauseDuringTempOutput() throws Exception {
    ToggleGate gate = ToggleGate.closed();
    Directory baseDir = new ByteBuffersDirectory();
    PausableDirectory pausableDir = new PausableDirectory(baseDir, gate);

    IndexOutput output = pausableDir.createTempOutput("test", ".tmp", IOContext.DEFAULT);

    AtomicBoolean writeCompleted = new AtomicBoolean(false);
    CountDownLatch writeStarted = new CountDownLatch(1);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(
          () -> {
            try {
              byte[] largeData = new byte[(int) PausableDirectory.CHECK_PAUSE_INTERVAL_BYTES];
              writeStarted.countDown();
              output.writeBytes(largeData, 0, largeData.length);
              writeCompleted.set(true);
            } catch (Exception e) {
              // Ignore
            }
          });

      assertTrue("Write should have started", writeStarted.await(5, TimeUnit.SECONDS));
      Thread.sleep(100);
      assertTrue("Write should be blocked when gate is closed", !writeCompleted.get());

      gate.open();

      executor.shutdown();
      assertTrue(
          "Write should complete after gate opens",
          executor.awaitTermination(5, TimeUnit.SECONDS));
      assertTrue("Write should have completed", writeCompleted.get());
    } finally {
      gate.open();
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    output.close();
    pausableDir.close();
  }

  /**
   * Test that different write methods (writeByte, writeInt, writeLong, etc.) all check for pause.
   */
  @Test
  public void testPauseCheckOnDifferentWriteMethods() throws IOException {
    Gate openGate = ToggleGate.opened();
    Directory baseDir = new ByteBuffersDirectory();
    PausableDirectory pausableDir = new PausableDirectory(baseDir, openGate);

    IndexOutput output = pausableDir.createOutput("test.dat", IOContext.DEFAULT);

    output.writeByte((byte) 1);
    output.writeShort((short) 2);
    output.writeInt(3);
    output.writeLong(4L);

    byte[] data = new byte[(int) PausableDirectory.CHECK_PAUSE_INTERVAL_BYTES];
    output.writeBytes(data, 0, data.length);

    output.close();

    long expectedSize =
        1
            + Short.BYTES
            + Integer.BYTES
            + Long.BYTES
            + PausableDirectory.CHECK_PAUSE_INTERVAL_BYTES;
    assertEquals(expectedSize, baseDir.fileLength("test.dat"));

    pausableDir.close();
  }

  /**
   * Test that PausableDirectory properly delegates getFilePointer and getChecksum.
   */
  @Test
  public void testDelegationMethods() throws IOException {
    Gate openGate = ToggleGate.opened();
    Directory baseDir = new ByteBuffersDirectory();
    PausableDirectory pausableDir = new PausableDirectory(baseDir, openGate);

    IndexOutput output = pausableDir.createOutput("test.dat", IOContext.DEFAULT);

    assertEquals(0, output.getFilePointer());

    byte[] data = new byte[100];
    output.writeBytes(data, 0, data.length);

    assertEquals(100, output.getFilePointer());

    long checksum = output.getChecksum();
    assertTrue("Checksum should be non-negative", checksum >= 0);

    output.close();
    pausableDir.close();
  }
}

