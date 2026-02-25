package com.xgen.mongot.embedding.mongodb.leasing;

import static com.xgen.mongot.embedding.config.MaterializedViewCollectionMetadata.MaterializedViewSchemaMetadata.VERSION_ZERO;
import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mongodb.MongoNamespace;
import com.xgen.mongot.embedding.config.MaterializedViewCollectionMetadata;
import com.xgen.mongot.embedding.config.MaterializedViewCollectionMetadata.MaterializedViewSchemaMetadata;
import com.xgen.mongot.index.EncodedUserData;
import com.xgen.mongot.index.status.IndexStatus;
import com.xgen.mongot.index.version.IndexFormatVersion;
import com.xgen.mongot.replication.mongodb.common.ChangeStreamResumeInfo;
import com.xgen.mongot.replication.mongodb.common.IndexCommitUserData;
import com.xgen.mongot.util.BsonUtils;
import com.xgen.mongot.util.FieldPath;
import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import com.xgen.testing.mongot.replication.mongodb.ChangeStreamUtils;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import org.bson.BsonTimestamp;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

/** Unit tests for {@link Lease}. */
@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {
      LeaseTest.TestDeserialization.class,
      LeaseTest.TestSerialization.class,
      LeaseTest.TestLease.class,
    })
public class LeaseTest {

  private static final String LEASE_ID = "test-lease-id";
  private static final String COLLECTION_UUID = "550e8400-e29b-41d4-a716-446655440000";
  private static final String COLLECTION_NAME = "test-collection";
  private static final String LEASE_OWNER = "localhost";
  private static final String RESOURCES_PATH = "src/test/unit/resources/embedding/mongodb/leasing";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {

    private static final String SUITE_NAME = "lease-deserialization";
    private static final BsonDeserializationTestSuite<Lease> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, Lease::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<Lease> testSpec;

    public TestDeserialization(BsonDeserializationTestSuite.TestSpecWrapper<Lease> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<Lease>> data() {
      return TEST_SUITE.withExamples(
          leaseWithMaterializedViewMetadata(), leaseWithEmptyMaterializedViewMetadata());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonDeserializationTestSuite.ValidSpec<Lease>
        leaseWithMaterializedViewMetadata() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "leaseWithMaterializedViewMetadata",
          new Lease(
              LEASE_ID,
              1L,
              COLLECTION_UUID,
              COLLECTION_NAME,
              LEASE_OWNER,
              Instant.ofEpochMilli(1733446635661L),
              Lease.FIRST_LEASE_VERSION,
              EncodedUserData.EMPTY.asString(),
              "1",
              Map.of(
                  "1",
                  new Lease.IndexDefinitionVersionStatus(
                      false, IndexStatus.StatusCode.INITIAL_SYNC)),
              new MaterializedViewCollectionMetadata(
                  new MaterializedViewSchemaMetadata(
                      1L, Map.of(FieldPath.parse("title"), FieldPath.parse("_autoEmbed.title"))),
                  UUID.fromString(COLLECTION_UUID),
                  COLLECTION_NAME),
              null));
    }

    private static BsonDeserializationTestSuite.ValidSpec<Lease>
        leaseWithEmptyMaterializedViewMetadata() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "leaseWithEmptyMaterializedViewMetadata",
          new Lease(
              LEASE_ID,
              1L,
              COLLECTION_UUID,
              COLLECTION_NAME,
              LEASE_OWNER,
              Instant.ofEpochMilli(1733446635661L),
              Lease.FIRST_LEASE_VERSION,
              EncodedUserData.EMPTY.asString(),
              "1",
              Map.of(
                  "1",
                  new Lease.IndexDefinitionVersionStatus(
                      false, IndexStatus.StatusCode.INITIAL_SYNC)),
              new MaterializedViewCollectionMetadata(
                  VERSION_ZERO, UUID.fromString(COLLECTION_UUID), COLLECTION_NAME),
              null));
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {

    private static final String SUITE_NAME = "lease-serialization";
    private static final BsonSerializationTestSuite<Lease> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<Lease> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<Lease> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<Lease>> data() {
      return Arrays.asList(
          leaseWithMaterializedViewMetadata(), leaseWithEmptyMaterializedViewMetadata());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<Lease> leaseWithMaterializedViewMetadata() {
      var customMetadata =
          new MaterializedViewCollectionMetadata(
              new MaterializedViewSchemaMetadata(
                  1L, Map.of(FieldPath.parse("title"), FieldPath.parse("_autoEmbed.title"))),
              UUID.fromString(COLLECTION_UUID),
              COLLECTION_NAME);
      var lease =
          new Lease(
              LEASE_ID,
              1L,
              COLLECTION_UUID,
              COLLECTION_NAME,
              LEASE_OWNER,
              Instant.parse("2024-12-06T00:57:15.661Z"), // Fixed timestamp for reproducibility
              Lease.FIRST_LEASE_VERSION,
              EncodedUserData.EMPTY.asString(),
              "1",
              Map.of(
                  "1",
                  new Lease.IndexDefinitionVersionStatus(
                      false, IndexStatus.StatusCode.INITIAL_SYNC)),
              customMetadata,
              null);
      return BsonSerializationTestSuite.TestSpec.create("leaseWithMaterializedViewMetadata", lease);
    }

    private static BsonSerializationTestSuite.TestSpec<Lease>
        leaseWithEmptyMaterializedViewMetadata() {
      var lease =
          new Lease(
              LEASE_ID,
              1L,
              COLLECTION_UUID,
              COLLECTION_NAME,
              LEASE_OWNER,
              Instant.parse("2024-12-06T00:57:15.661Z"), // Fixed timestamp for reproducibility
              Lease.FIRST_LEASE_VERSION,
              EncodedUserData.EMPTY.asString(),
              "1",
              Map.of(
                  "1",
                  new Lease.IndexDefinitionVersionStatus(
                      false, IndexStatus.StatusCode.INITIAL_SYNC)),
              new MaterializedViewCollectionMetadata(
                  VERSION_ZERO, UUID.fromString(COLLECTION_UUID), COLLECTION_NAME),
              null);
      return BsonSerializationTestSuite.TestSpec.create(
          "leaseWithEmptyMaterializedViewMetadata", lease);
    }
  }

  public static class TestLease {
    @Test
    public void testExtractHighWaterMarkFromSteadyState() {
      // Set up a lease with steady state commitInfo containing a resume token
      var opTime = new BsonTimestamp(9876543210L);
      var resumeToken = ChangeStreamUtils.resumeToken(opTime);
      var indexCommitUserData =
          IndexCommitUserData.createChangeStreamResume(
              ChangeStreamResumeInfo.create(new MongoNamespace("test", "collection"), resumeToken),
              IndexFormatVersion.CURRENT);

      var lease = createLeaseWithCommitInfo(indexCommitUserData.toEncodedData().asString());

      var extractedHighWaterMark = lease.extractHighWaterMark();

      assertTrue(extractedHighWaterMark.isPresent());
      assertEquals(opTime, extractedHighWaterMark.get());
    }

    @Test
    public void testExtractHighWaterMarkFromEmptyCommitInfo() {
      // Lease with empty commitInfo (V1 hasn't started yet)
      var lease = createLeaseWithCommitInfo(EncodedUserData.EMPTY.asString());

      var extractedHighWaterMark = lease.extractHighWaterMark();

      assertFalse(extractedHighWaterMark.isPresent());
    }

    @Test
    public void testWithNewIndexDefinitionVersionPreservesHighWaterMarkFromSteadyState() {
      // Set up a lease with steady state commitInfo
      var opTime = new BsonTimestamp(9876543210L);
      var resumeToken = ChangeStreamUtils.resumeToken(opTime);
      var indexCommitUserData =
          IndexCommitUserData.createChangeStreamResume(
              ChangeStreamResumeInfo.create(new MongoNamespace("test", "collection"), resumeToken),
              IndexFormatVersion.CURRENT);

      var lease = createLeaseWithCommitInfo(indexCommitUserData.toEncodedData().asString());

      // Create V2 lease
      var v2Lease = lease.withNewIndexDefinitionVersion("2", IndexStatus.initialSync());

      // Parse V2's commitInfo and verify it contains the preserved highWaterMark
      var encodedUserData = EncodedUserData.fromString(v2Lease.commitInfo());
      var userData = IndexCommitUserData.fromEncodedData(encodedUserData, Optional.empty());

      assertTrue(userData.getInitialSyncResumeInfo().isPresent());
      assertEquals(opTime, userData.getInitialSyncResumeInfo().get().getResumeOperationTime());
      // Verify scan position is reset to MIN_KEY
      assertEquals(BsonUtils.MIN_KEY, userData.getInitialSyncResumeInfo().get().getResumeToken());
    }

    @Test
    public void testWithNewIndexDefinitionVersionWithEmptyCommitInfo() {
      // Lease with empty commitInfo (V1 hasn't started yet)
      var lease = createLeaseWithCommitInfo(EncodedUserData.EMPTY.asString());

      // Create V2 lease
      var v2Lease = lease.withNewIndexDefinitionVersion("2", IndexStatus.initialSync());

      // V2's commitInfo should also be empty since there's no highWaterMark to preserve
      assertEquals(EncodedUserData.EMPTY.asString(), v2Lease.commitInfo());
    }

    @Test
    public void testSteadyAsOfOplogPosition_serialization() {
      // Field present - serialized to BSON correctly
      BsonTimestamp position = new BsonTimestamp(1000, 1);
      Lease leaseWithPosition = createLeaseWithSteadyPosition(position);

      var bson = leaseWithPosition.toBson();
      assertTrue(bson.containsKey("steadyAsOfOplogPosition"));
      assertEquals(position, bson.getTimestamp("steadyAsOfOplogPosition"));

      // Field absent - omitted from BSON
      Lease leaseWithoutPosition = createLeaseWithSteadyPosition(null);

      var bsonEmpty = leaseWithoutPosition.toBson();
      assertFalse(bsonEmpty.containsKey("steadyAsOfOplogPosition"));
    }

    @Test
    public void testWithUpdatedStatus_steadyAsOfOplogPositionLifecycle() {
      BsonTimestamp firstSteadyPosition = new BsonTimestamp(1000, 1);
      BsonTimestamp laterPosition = new BsonTimestamp(2000, 1);

      // Start with no position
      Lease initialLease = createLeaseWithSteadyPosition(null);
      assertFalse(initialLease.getSteadyAsOfOplogPosition().isPresent());

      // Non-STEADY transition doesn't set position
      Lease afterInitialSync =
          initialLease.withUpdatedStatus(IndexStatus.initialSync(), 1L, firstSteadyPosition);
      assertFalse(afterInitialSync.getSteadyAsOfOplogPosition().isPresent());

      // First STEADY transition sets position
      Lease afterFirstSteady =
          afterInitialSync.withUpdatedStatus(IndexStatus.steady(), 1L, firstSteadyPosition);
      assertTrue(afterFirstSteady.getSteadyAsOfOplogPosition().isPresent());
      assertEquals(firstSteadyPosition, afterFirstSteady.getSteadyAsOfOplogPosition().get());

      // Subsequent STEADY transition preserves original position
      Lease afterSecondSteady =
          afterFirstSteady.withUpdatedStatus(IndexStatus.steady(), 1L, laterPosition);
      assertTrue(afterSecondSteady.getSteadyAsOfOplogPosition().isPresent());
      assertEquals(firstSteadyPosition, afterSecondSteady.getSteadyAsOfOplogPosition().get());
    }

    @Test
    public void testSteadyAsOfOplogPositionLifecycle() {
      // newLease starts with null steadyAsOfOplogPosition
      var newLease =
          Lease.newLease(
              LEASE_ID,
              UUID.fromString(COLLECTION_UUID),
              COLLECTION_NAME,
              LEASE_OWNER,
              "1",
              IndexStatus.initialSync(),
              new MaterializedViewCollectionMetadata(
                  VERSION_ZERO, UUID.fromString(COLLECTION_UUID), COLLECTION_NAME));
      assertFalse(
          "New lease should have empty steadyAsOfOplogPosition",
          newLease.getSteadyAsOfOplogPosition().isPresent());

      // withUpdatedCheckpoint preserves steadyAsOfOplogPosition
      var steadyPosition = new BsonTimestamp(1234567890L);
      var leaseWithSteadyPosition = createLeaseWithSteadyPosition(steadyPosition);
      var afterCheckpoint = leaseWithSteadyPosition.withUpdatedCheckpoint(EncodedUserData.EMPTY);
      assertTrue(
          "withUpdatedCheckpoint should preserve steadyAsOfOplogPosition",
          afterCheckpoint.getSteadyAsOfOplogPosition().isPresent());
      assertEquals(steadyPosition, afterCheckpoint.getSteadyAsOfOplogPosition().get());

      // withNewIndexDefinitionVersion resets steadyAsOfOplogPosition to null
      var afterNewVersion =
          leaseWithSteadyPosition.withNewIndexDefinitionVersion("2", IndexStatus.initialSync());
      assertFalse(
          "withNewIndexDefinitionVersion should reset steadyAsOfOplogPosition",
          afterNewVersion.getSteadyAsOfOplogPosition().isPresent());
    }

    private static Lease createLeaseWithCommitInfo(String commitInfo) {
      return new Lease(
          LEASE_ID,
          Lease.FIRST_LEASE_VERSION,
          COLLECTION_UUID,
          COLLECTION_NAME,
          LEASE_OWNER,
          Instant.now(),
          Lease.FIRST_LEASE_VERSION,
          commitInfo,
          "1",
          Map.of(
              "1",
              new Lease.IndexDefinitionVersionStatus(false, IndexStatus.StatusCode.INITIAL_SYNC)),
          new MaterializedViewCollectionMetadata(
              VERSION_ZERO, UUID.fromString(COLLECTION_UUID), COLLECTION_NAME),
          null);
    }

    private static Lease createLeaseWithSteadyPosition(@Nullable BsonTimestamp steadyPosition) {
      return new Lease(
          LEASE_ID,
          Lease.FIRST_LEASE_VERSION,
          COLLECTION_UUID,
          COLLECTION_NAME,
          LEASE_OWNER,
          Instant.now(),
          Lease.FIRST_LEASE_VERSION,
          "",
          "1",
          Map.of(
              "1",
              new Lease.IndexDefinitionVersionStatus(false, IndexStatus.StatusCode.INITIAL_SYNC)),
          new MaterializedViewCollectionMetadata(
              VERSION_ZERO, UUID.fromString(COLLECTION_UUID), COLLECTION_NAME),
          steadyPosition);
    }
  }
}
