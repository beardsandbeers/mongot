package com.xgen.mongot.index.lucene;

import static org.junit.Assert.assertEquals;

import com.xgen.mongot.index.SearchHighlight;
import com.xgen.testing.BsonTestSuite;
import com.xgen.testing.mongot.index.path.string.StringPathBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.util.BytesRef;
import org.bson.BsonArray;
import org.bson.BsonValue;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class LuceneSearchHighlightFormatterTest {

  private static final String TEST_CONTENT_SINGLE = "single";
  private static final String TEST_CONTENT = "Imagine all the people living life in peace.";
  private static final float TEST_SCORE = 1.0f;

  private static final BsonTestSuite TEST_SUITE =
      BsonTestSuite.load("src/test/unit/resources/index/lucene", "searchHighlightsFormatter");
  private static final Map<String, LuceneSearchHighlights> GENERATED = new HashMap<>();

  private static Passage[] createPassages(String[] hits, String content) {
    Passage passage = new Passage();
    passage.setScore(TEST_SCORE);
    passage.setStartOffset(0);
    passage.setEndOffset(content.length());

    for (int i = 0; i < hits.length; i++) {
      String hit = hits[i];

      int hitBegin = content.indexOf(hit);
      int hitEnd = content.indexOf(hit) + hit.length();

      passage.addMatch(hitBegin, hitEnd, new BytesRef(hit), 1);
    }

    Passage[] passages = new Passage[1];
    passages[0] = passage;
    return passages;
  }

  /** Initializes the expected test results. */
  @BeforeClass
  public static void setUpClass() {

    LuceneSearchHighlightsFormatter formatter = new LuceneSearchHighlightsFormatter();

    String[] oneHitOnly = {"single"};
    GENERATED.put(
        "oneHitOnly",
        (LuceneSearchHighlights)
            formatter.format(createPassages(oneHitOnly, TEST_CONTENT_SINGLE), TEST_CONTENT_SINGLE));

    String[] oneTextOneHitAtBegin = {"Imagine"};
    GENERATED.put(
        "oneTextOneHitAtBegin",
        (LuceneSearchHighlights)
            formatter.format(createPassages(oneTextOneHitAtBegin, TEST_CONTENT), TEST_CONTENT));

    String[] oneTextOneHitAtEnd = {"peace"};
    GENERATED.put(
        "oneTextOneHitAtEnd",
        (LuceneSearchHighlights)
            formatter.format(createPassages(oneTextOneHitAtEnd, TEST_CONTENT), TEST_CONTENT));

    String[] twoTextsOneHit = {"people"};
    GENERATED.put(
        "twoTextsOneHit",
        (LuceneSearchHighlights)
            formatter.format(createPassages(twoTextsOneHit, TEST_CONTENT), TEST_CONTENT));

    String[] twoTextsTwoHitsOneAtBegin = {"Imagine", "people"};
    GENERATED.put(
        "twoTextsTwoHitsOneAtBegin",
        (LuceneSearchHighlights)
            formatter.format(
                createPassages(twoTextsTwoHitsOneAtBegin, TEST_CONTENT), TEST_CONTENT));

    String[] twoTextsTwoHitsOneAtEnd = {"people", "peace"};
    GENERATED.put(
        "twoTextsTwoHitsOneAtEnd",
        (LuceneSearchHighlights)
            formatter.format(createPassages(twoTextsTwoHitsOneAtEnd, TEST_CONTENT), TEST_CONTENT));

    String[] threeHitsTwoTexts = {"Imagine", "people", "peace"};
    GENERATED.put(
        "threeHitsTwoTexts",
        (LuceneSearchHighlights)
            formatter.format(createPassages(threeHitsTwoTexts, TEST_CONTENT), TEST_CONTENT));

    String[] noHits = {};
    GENERATED.put(
        "noHits",
        (LuceneSearchHighlights)
            formatter.format(createPassages(noHits, TEST_CONTENT), TEST_CONTENT));
  }

  @Test
  public void testValid() {
    for (BsonTestSuite.TestCase testCase : TEST_SUITE.valid) {
      BsonValue actual =
          highlightsToBson(
              GENERATED
                  .get(testCase.getDescription())
                  .toSearchHighlights(StringPathBuilder.fieldPath("testPath")));
      BsonValue expected = testCase.getValue();
      assertEquals(testCase.getDescription(), expected, actual);
    }
  }

  private static BsonValue highlightsToBson(List<SearchHighlight> highlights) {
    return new BsonArray(
        highlights.stream().map(SearchHighlight::toBson).collect(Collectors.toList()));
  }
}
