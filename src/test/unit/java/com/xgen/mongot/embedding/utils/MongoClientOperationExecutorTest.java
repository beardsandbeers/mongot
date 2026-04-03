package com.xgen.mongot.embedding.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.bulk.BulkWriteError;
import com.xgen.mongot.embedding.exceptions.MaterializedViewTransientException;
import com.xgen.mongot.metrics.MetricsFactory;
import com.xgen.mongot.util.mongodb.Errors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.Test;

public class MongoClientOperationExecutorTest {

  private static final String PREFIX = "test";

  private MongoClientOperationExecutor newExecutor(SimpleMeterRegistry registry) {
    return new MongoClientOperationExecutor(new MetricsFactory(PREFIX, registry), "resource");
  }

  private static MongoCommandException commandException(int code, String msg) {
    return new MongoCommandException(
        new BsonDocument("ok", new BsonInt32(0))
            .append("errmsg", new BsonString(msg))
            .append("code", new BsonInt32(code)),
        new ServerAddress());
  }

  private static MongoBulkWriteException bulkWriteException(int code, String msg) {
    BulkWriteError error = new BulkWriteError(code, msg, new BsonDocument(), 0);
    MongoBulkWriteException ex = mock(MongoBulkWriteException.class);
    when(ex.getWriteErrors()).thenReturn(List.of(error));
    return ex;
  }

  private static MongoWriteException writeException(int code, String msg) {
    return new MongoWriteException(
        new WriteError(code, msg, new BsonDocument()), new ServerAddress());
  }

  // --- ExceededDiskLimit (14031) via MongoCommandException ---

  @Test
  public void diskFull_commandException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "bulkWrite",
                () -> {
                  throw commandException(Errors.EXCEEDED_DISK_LIMIT.code, "disk limit exceeded");
                }));
  }

  @Test
  public void diskFull_commandException_incrementsMetric() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    try {
      executor.execute(
          "bulkWrite",
          () -> {
            throw commandException(Errors.EXCEEDED_DISK_LIMIT.code, "disk limit exceeded");
          });
    } catch (MaterializedViewTransientException ignored) {
      // ignored
    }

    Counter counter = registry.find(PREFIX + ".mongodDiskFullErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  // --- ExceededDiskLimit (14031) via MongoBulkWriteException ---

  @Test
  public void diskFull_bulkWriteException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "bulkWrite",
                () -> {
                  throw bulkWriteException(
                      Errors.EXCEEDED_DISK_LIMIT.code, "disk limit exceeded");
                }));
  }

  @Test
  public void diskFull_bulkWriteException_incrementsMetric() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    try {
      executor.execute(
          "bulkWrite",
          () -> {
            throw bulkWriteException(Errors.EXCEEDED_DISK_LIMIT.code, "disk limit exceeded");
          });
    } catch (MaterializedViewTransientException ignored) {
      // ignored
    }

    Counter counter = registry.find(PREFIX + ".mongodDiskFullErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  // --- UserWritesBlocked (371) via MongoCommandException ---

  @Test
  public void userWritesBlocked_commandException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "deleteOne",
                () -> {
                  throw commandException(Errors.USER_WRITES_BLOCKED.code, "user writes blocked");
                }));
  }

  @Test
  public void userWritesBlocked_commandException_incrementsMetric() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    try {
      executor.execute(
          "deleteOne",
          () -> {
            throw commandException(Errors.USER_WRITES_BLOCKED.code, "user writes blocked");
          });
    } catch (MaterializedViewTransientException ignored) {
      // ignored
    }

    Counter counter = registry.find(PREFIX + ".mongodUserWritesBlockedErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  // --- UserWritesBlocked (371) via MongoBulkWriteException ---

  @Test
  public void userWritesBlocked_bulkWriteException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "bulkWrite",
                () -> {
                  throw bulkWriteException(
                      Errors.USER_WRITES_BLOCKED.code, "user writes blocked");
                }));
  }

  @Test
  public void userWritesBlocked_bulkWriteException_incrementsMetric() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    try {
      executor.execute(
          "bulkWrite",
          () -> {
            throw bulkWriteException(Errors.USER_WRITES_BLOCKED.code, "user writes blocked");
          });
    } catch (MaterializedViewTransientException ignored) {
      // ignored
    }

    Counter counter = registry.find(PREFIX + ".mongodUserWritesBlockedErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  // --- SystemOverloadedError codes (433, 449, 450, 473) ---

  @Test
  public void admissionQueueOverflow_commandException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "bulkWrite",
                () -> {
                  throw commandException(
                      Errors.ADMISSION_QUEUE_OVERFLOW.code, "admission queue overflow");
                }));

    Counter counter = registry.find(PREFIX + ".mongodSystemOverloadErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  @Test
  public void interruptedDueToOverload_commandException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "deleteOne",
                () -> {
                  throw commandException(
                      Errors.INTERRUPTED_DUE_TO_OVERLOAD.code, "interrupted due to overload");
                }));

    Counter counter = registry.find(PREFIX + ".mongodSystemOverloadErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  @Test
  public void systemOverload_bulkWriteException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "bulkWrite",
                () -> {
                  throw bulkWriteException(
                      Errors.ADMISSION_QUEUE_OVERFLOW.code, "admission queue overflow");
                }));

    Counter counter = registry.find(PREFIX + ".mongodSystemOverloadErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  // --- ExceededDiskLimit (14031) via MongoWriteException ---

  @Test
  public void diskFull_writeException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "bulkWrite",
                () -> {
                  throw writeException(Errors.EXCEEDED_DISK_LIMIT.code, "disk limit exceeded");
                }));

    Counter counter = registry.find(PREFIX + ".mongodDiskFullErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  // --- UserWritesBlocked (371) via MongoWriteException ---

  @Test
  public void userWritesBlocked_writeException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "deleteOne",
                () -> {
                  throw writeException(
                      Errors.USER_WRITES_BLOCKED.code, "user writes blocked");
                }));

    Counter counter = registry.find(PREFIX + ".mongodUserWritesBlockedErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  // --- SystemOverloadedError codes via MongoWriteException ---

  @Test
  public void systemOverload_writeException_throwsTransient() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MaterializedViewTransientException.class,
        () ->
            executor.execute(
                "bulkWrite",
                () -> {
                  throw writeException(
                      Errors.ADMISSION_QUEUE_OVERFLOW.code, "admission queue overflow");
                }));

    Counter counter = registry.find(PREFIX + ".mongodSystemOverloadErrors").counter();
    assertNotNull(counter);
    assertEquals(1, (int) counter.count());
  }

  // --- Other errors are not intercepted ---

  @Test
  public void otherMongoCommandException_rethrowsUnwrapped() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MongoClientOperationExecutor executor = newExecutor(registry);

    assertThrows(
        MongoCommandException.class,
        () ->
            executor.execute(
                "findOne",
                () -> {
                  throw commandException(2, "bad value");
                }));
  }
}
