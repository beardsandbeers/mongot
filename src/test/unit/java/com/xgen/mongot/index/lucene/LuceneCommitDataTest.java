package com.xgen.mongot.index.lucene;

import com.google.common.collect.ImmutableMap;
import com.xgen.mongot.index.EncodedUserData;
import com.xgen.mongot.index.lucene.commit.LuceneCommitData;
import java.util.Map;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class LuceneCommitDataTest {
  @Test
  public void testFromUserDataMap_withoutIndexWriterData() {
    Map<String, String> dataMap =
        ImmutableMap.of(LuceneCommitData.Keys.USER_DATA, "encodedUserData");
    LuceneCommitData luceneCommitData =
        LuceneCommitData.fromDataMap(Optional.of(dataMap.entrySet()));
    Assert.assertEquals("encodedUserData", luceneCommitData.getEncodedUserData().asString());
    Assert.assertEquals(
        LuceneCommitData.IndexWriterData.EMPTY, luceneCommitData.getIndexWriterData());
  }

  @Test
  public void testFromUserDataMap_withIndexWriterData() {
    LuceneCommitData luceneCommitData1 =
        LuceneCommitData.fromDataMap(
            Optional.of(
                ImmutableMap.of(
                        LuceneCommitData.Keys.USER_DATA,
                        "encodedUserData",
                        LuceneCommitData.Keys.INDEX_WRITER_DATA,
                        "{\"isCleared\": true}")
                    .entrySet()));
    Assert.assertEquals("encodedUserData", luceneCommitData1.getEncodedUserData().asString());
    Assert.assertEquals(
        new LuceneCommitData.IndexWriterData(true), luceneCommitData1.getIndexWriterData());

    LuceneCommitData luceneCommitData2 =
        LuceneCommitData.fromDataMap(
            Optional.of(
                ImmutableMap.of(
                        LuceneCommitData.Keys.USER_DATA,
                        "encodedUserData",
                        LuceneCommitData.Keys.INDEX_WRITER_DATA,
                        "{isCleared: false}")
                    .entrySet()));
    Assert.assertEquals("encodedUserData", luceneCommitData2.getEncodedUserData().asString());
    Assert.assertEquals(
        new LuceneCommitData.IndexWriterData(false), luceneCommitData2.getIndexWriterData());

    LuceneCommitData luceneCommitData3 =
        LuceneCommitData.fromDataMap(
            Optional.of(
                ImmutableMap.of(
                        LuceneCommitData.Keys.USER_DATA,
                        "encodedUserData",
                        LuceneCommitData.Keys.INDEX_WRITER_DATA,
                        "invalid json")
                    .entrySet()));
    // Returns empty data if we failed to parse IndexWriterData.
    Assert.assertEquals(EncodedUserData.EMPTY, luceneCommitData3.getEncodedUserData());
    Assert.assertEquals(
        LuceneCommitData.IndexWriterData.EMPTY, luceneCommitData3.getIndexWriterData());
  }

  @Test
  public void testFromUserDataMap_emptyUserDataMap() {
    LuceneCommitData luceneCommitData1 = LuceneCommitData.fromDataMap(Optional.empty());
    Assert.assertEquals(EncodedUserData.EMPTY, luceneCommitData1.getEncodedUserData());
    Assert.assertEquals(
        LuceneCommitData.IndexWriterData.EMPTY, luceneCommitData1.getIndexWriterData());

    LuceneCommitData luceneCommitData2 =
        LuceneCommitData.fromDataMap(Optional.of(ImmutableMap.<String, String>of().entrySet()));
    Assert.assertEquals(EncodedUserData.EMPTY, luceneCommitData2.getEncodedUserData());
    Assert.assertEquals(
        LuceneCommitData.IndexWriterData.EMPTY, luceneCommitData2.getIndexWriterData());
  }

  @Test
  public void testToDataMapEntries() {
    LuceneCommitData luceneCommitData1 =
        new LuceneCommitData(
            LuceneCommitData.IndexWriterData.EMPTY, EncodedUserData.fromString("encodedUserData"));
    Assert.assertEquals(
        ImmutableMap.of(
                LuceneCommitData.Keys.USER_DATA,
                "encodedUserData",
                LuceneCommitData.Keys.INDEX_WRITER_DATA,
                "{\"isCleared\": false}")
            .entrySet(),
        luceneCommitData1.toDataMapEntries());

    LuceneCommitData luceneCommitData2 =
        new LuceneCommitData(
            new LuceneCommitData.IndexWriterData(true),
            EncodedUserData.fromString("encodedUserData"));
    Assert.assertEquals(
        ImmutableMap.of(
                LuceneCommitData.Keys.USER_DATA,
                "encodedUserData",
                LuceneCommitData.Keys.INDEX_WRITER_DATA,
                "{\"isCleared\": true}")
            .entrySet(),
        luceneCommitData2.toDataMapEntries());
  }
}
