package com.xgen.mongot.index;

import static com.xgen.testing.BsonDeserializationTestSuite.fromDocument;
import static com.xgen.testing.BsonSerializationTestSuite.fromEncodable;

import com.xgen.testing.BsonDeserializationTestSuite;
import com.xgen.testing.BsonSerializationTestSuite;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses(
    value = {CountResultTest.TestDeserialization.class, CountResultTest.TestSerialization.class})
public class CountResultTest {

  static final String RESOURCES_PATH = "src/test/unit/resources/index";

  @RunWith(Parameterized.class)
  public static class TestDeserialization {
    private static final String SUITE_NAME = "count-deserialization";

    private static final BsonDeserializationTestSuite<CountResult> TEST_SUITE =
        fromDocument(RESOURCES_PATH, SUITE_NAME, CountResult::fromBson);

    private final BsonDeserializationTestSuite.TestSpecWrapper<CountResult> testSpec;

    public TestDeserialization(BsonDeserializationTestSuite.TestSpecWrapper<CountResult> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonDeserializationTestSuite.TestSpecWrapper<CountResult>> data() {
      return TEST_SUITE.withExamples(lowerBound(), total(), both());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    @Test
    public void testMergeTotalCount() {
      List<CountResult> countResults = new ArrayList<>();
      countResults.add(CountResult.totalCount(1));
      countResults.add(CountResult.totalCount(11));
      countResults.add(CountResult.totalCount(38));
      var mergedCount = CountResult.merge(countResults);
      Assert.assertEquals(CountResult.totalCount(50), mergedCount);
    }

    @Test
    public void testMergeLowerBoundCount() {
      List<CountResult> countResults = new ArrayList<>();
      countResults.add(CountResult.lowerBoundCount(1));
      countResults.add(CountResult.lowerBoundCount(11));
      countResults.add(CountResult.lowerBoundCount(30));
      var mergedCount = CountResult.merge(countResults);
      Assert.assertEquals(CountResult.lowerBoundCount(42), mergedCount);
    }

    @Test
    public void testMergeMixedCount() {
      List<CountResult> countResults = new ArrayList<>();
      countResults.add(CountResult.totalCount(1));
      countResults.add(CountResult.totalCount(11));
      countResults.add(CountResult.lowerBoundCount(30));
      var mergedCount = CountResult.merge(countResults);
      Assert.assertEquals(CountResult.lowerBoundCount(42), mergedCount);
    }

    private static BsonDeserializationTestSuite.ValidSpec<CountResult> lowerBound() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "lowerBound", CountResult.lowerBoundCount(1000));
    }

    private static BsonDeserializationTestSuite.ValidSpec<CountResult> total() {
      return BsonDeserializationTestSuite.TestSpec.valid("total", CountResult.totalCount(1000));
    }

    private static BsonDeserializationTestSuite.ValidSpec<CountResult> both() {
      return BsonDeserializationTestSuite.TestSpec.valid(
          "both", new CountResult(Optional.of(1000L), Optional.of(1000L)));
    }
  }

  @RunWith(Parameterized.class)
  public static class TestSerialization {
    private static final String SUITE_NAME = "count-serialization";
    private static final BsonSerializationTestSuite<CountResult> TEST_SUITE =
        fromEncodable(RESOURCES_PATH, SUITE_NAME);

    private final BsonSerializationTestSuite.TestSpec<CountResult> testSpec;

    public TestSerialization(BsonSerializationTestSuite.TestSpec<CountResult> testSpec) {
      this.testSpec = testSpec;
    }

    /** Test data. */
    @Parameterized.Parameters(name = "{0}")
    public static Iterable<BsonSerializationTestSuite.TestSpec<CountResult>> data() {
      return Arrays.asList(lowerBound(), total(), both());
    }

    @Test
    public void runTest() throws Exception {
      TEST_SUITE.runTest(this.testSpec);
    }

    private static BsonSerializationTestSuite.TestSpec<CountResult> lowerBound() {
      return BsonSerializationTestSuite.TestSpec.create(
          "lowerBound", CountResult.lowerBoundCount(2147483648L));
    }

    private static BsonSerializationTestSuite.TestSpec<CountResult> total() {
      return BsonSerializationTestSuite.TestSpec.create(
          "total", CountResult.totalCount(2147483648L));
    }

    private static BsonSerializationTestSuite.TestSpec<CountResult> both() {
      return BsonSerializationTestSuite.TestSpec.create(
          "both", new CountResult(Optional.of(2147483648L), Optional.of(2147483648L)));
    }
  }
}
