package com.xgen.mongot.index.lucene.explain.query;

import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.index.IndexMetricsUpdater;
import com.xgen.mongot.index.lucene.query.custom.MongotKnnFloatQuery;
import com.xgen.testing.mongot.mock.index.SearchIndex;
import java.util.Optional;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.Assert;
import org.junit.Test;

public class ExplainQueryExecutionContextTest {

  private static final IndexMetricsUpdater.QueryingMetricsUpdater metrics =
      new IndexMetricsUpdater.QueryingMetricsUpdater(SearchIndex.mockMetricsFactory());

  @Test
  public void testSimple() throws Exception {
    QueryVisitorQueryExecutionContext context = new QueryVisitorQueryExecutionContext();

    var query = termFor("a");
    var expected = QueryTreeTestUtils.QueryTreeNode.builder(query).build();
    context.getOrCreateNode(query);

    test(context, expected);
  }

  @Test
  public void testSimpleNested() throws Exception {
    var a = termFor("a");
    var b = termFor("b");
    var c = termFor("c");
    var d = termFor("d");

    var query =
        new BooleanQuery.Builder()
            .add(a, BooleanClause.Occur.MUST)
            .add(b, BooleanClause.Occur.MUST_NOT)
            .add(c, BooleanClause.Occur.SHOULD)
            .add(d, BooleanClause.Occur.FILTER)
            .build();

    var context = new QueryVisitorQueryExecutionContext();
    context.getOrCreateNode(query);

    assertNodesForQueries(context, a, b, c, d, query);

    var expected =
        QueryTreeTestUtils.QueryTreeNode.builder(query)
            .children(QueryTreeTestUtils.Children.create().must(a).mustNot(b).should(c).filter(d))
            .build();

    test(context, expected);
  }

  @Test
  public void testDeepNested() {
    var a = termFor("a");
    var b = termFor("b");
    var c = termFor("c");
    var d = termFor("d");

    var ab =
        new BooleanQuery.Builder()
            .add(a, BooleanClause.Occur.SHOULD)
            .add(b, BooleanClause.Occur.SHOULD)
            .build();

    var cd =
        new BooleanQuery.Builder()
            .add(c, BooleanClause.Occur.SHOULD)
            .add(d, BooleanClause.Occur.SHOULD)
            .build();

    var query =
        new BooleanQuery.Builder()
            .add(ab, BooleanClause.Occur.MUST)
            .add(cd, BooleanClause.Occur.MUST)
            .build();

    var context = new QueryVisitorQueryExecutionContext();
    context.getOrCreateNode(query);

    assertNodesForQueries(context, a, b, c, d, ab, cd, query);

    var expected =
        QueryTreeTestUtils.QueryTreeNode.builder(query)
            .children(
                QueryTreeTestUtils.Children.create()
                    .must(
                        QueryTreeTestUtils.QueryTreeNode.builder(ab)
                            .children(QueryTreeTestUtils.Children.create().should(a).should(b))
                            .build())
                    .must(
                        QueryTreeTestUtils.QueryTreeNode.builder(cd)
                            .children(QueryTreeTestUtils.Children.create().should(c).should(d))
                            .build()))
            .build();

    test(context, expected);
  }

  @Test
  public void testReplaceNode() {
    var executionContext = new QueryVisitorQueryExecutionContext();

    var a = termFor("a");
    var b = termFor("b");
    var c = termFor("c");

    var query =
        new BooleanQuery.Builder()
            .add(a, BooleanClause.Occur.SHOULD)
            .add(b, BooleanClause.Occur.SHOULD)
            .build();

    executionContext.getOrCreateNode(query);
    assertNodesForQueries(executionContext, a, b, query);
    assertNoNodesForQueries(executionContext, c);

    executionContext.replaceNode(b, c);
    assertNodesForQueries(executionContext, a, c, query);
    assertNoNodesForQueries(executionContext, b);

    var expectedRoot =
        QueryTreeTestUtils.QueryTreeNode.builder(query)
            .children(QueryTreeTestUtils.Children.create().should(a).should(c))
            .build();
    test(executionContext, expectedRoot);
  }

  @Test
  public void testReplaceDeeplyNestedNode() {
    var executionContext = new QueryVisitorQueryExecutionContext();

    /*
    {
     query: {
       must: {
         b: {
           filter: {
             c: {
               should: {
                 d: {
                   mustNot: "term(y)"
                 }
               }
             }
           },
           must: "term(z)"
         }
       }
     }
    }
    */
    var z = termFor("z");
    var y = termFor("y");
    var d = new BooleanQuery.Builder().add(y, BooleanClause.Occur.MUST_NOT).build();
    var c = new BooleanQuery.Builder().add(d, BooleanClause.Occur.SHOULD).build();
    var b =
        new BooleanQuery.Builder()
            .add(c, BooleanClause.Occur.FILTER)
            .add(z, BooleanClause.Occur.MUST)
            .build();
    var query = new BooleanQuery.Builder().add(b, BooleanClause.Occur.MUST).build();

    /*
    {
      replacement: {
        filter: "term(x)"
      }
    }
    */
    var x = termFor("x");
    var replacement = new BooleanQuery.Builder().add(x, BooleanClause.Occur.FILTER).build();

    executionContext.getOrCreateNode(query);
    assertNodesForQueries(executionContext, z, y, d, c, b, query);
    assertNoNodesForQueries(executionContext, replacement, x);

    executionContext.replaceNode(c, replacement);
    assertNodesForQueries(executionContext, z, b, replacement, x, query);
    assertNoNodesForQueries(executionContext, y, d, c);

    /*
    {
      query: {
        must: {
          b: {
            filter: {
              replacement: {
                filter: "term(x)"
              }
            },
            must: "term(z)"
          }
        }
      }
    }
    */
    var expectedRoot =
        QueryTreeTestUtils.QueryTreeNode.builder(query)
            .children(
                QueryTreeTestUtils.Children.create()
                    .must(
                        QueryTreeTestUtils.QueryTreeNode.builder(b)
                            .children(
                                QueryTreeTestUtils.Children.create()
                                    .filter(
                                        QueryTreeTestUtils.QueryTreeNode.builder(replacement)
                                            .children(
                                                QueryTreeTestUtils.Children.create().filter(x))
                                            .build())
                                    .must(z))
                            .build()))
            .build();
    test(executionContext, expectedRoot);
  }

  @Test
  public void testaddChildNode() {
    String path = "path";
    float[] target = {1, 2, 3};
    int k = 100;
    var executionContext = new QueryVisitorQueryExecutionContext();
    Query knnQuery = new MongotKnnFloatQuery(metrics, path, target, k);
    Query rewritten = new MatchNoDocsQuery();
    executionContext.getOrCreateNode(knnQuery);
    assertNodesForQueries(executionContext, knnQuery);
    executionContext.addChildNode(knnQuery, rewritten, BooleanClause.Occur.MUST);
    var expectedRoot =
        QueryTreeTestUtils.QueryTreeNode.builder(knnQuery)
            .children(
                QueryTreeTestUtils.Children.create()
                    .must(QueryTreeTestUtils.QueryTreeNode.builder(rewritten).build()))
            .build();
    test(executionContext, expectedRoot);
  }

  @Test
  public void testaddChildNodeWithFilter() {
    String path = "path";
    float[] target = {1, 2, 3};
    int k = 100;
    var executionContext = new QueryVisitorQueryExecutionContext();
    Query subQuery = termFor("term");
    Query filter =
        new BooleanQuery.Builder()
            .add(new BooleanClause(subQuery, BooleanClause.Occur.MUST))
            .build();
    Query knnQuery =
        new MongotKnnFloatQuery(metrics, FeatureFlags.getDefault(), path, target, k, filter);
    Query rewritten = new MatchNoDocsQuery();
    executionContext.getOrCreateNode(knnQuery);
    assertNodesForQueries(executionContext, knnQuery);
    executionContext.addChildNode(knnQuery, rewritten, BooleanClause.Occur.MUST);
    executionContext.addChildNode(knnQuery, filter, BooleanClause.Occur.FILTER);
    var expectedRoot =
        QueryTreeTestUtils.QueryTreeNode.builder(knnQuery)
            .children(
                QueryTreeTestUtils.Children.create()
                    .filter(
                        QueryTreeTestUtils.QueryTreeNode.builder(filter)
                            .children(QueryTreeTestUtils.Children.create().must(subQuery))
                            .build())
                    .must(QueryTreeTestUtils.QueryTreeNode.builder(rewritten).build()))
            .build();
    test(executionContext, expectedRoot);
  }

  static void test(
      QueryVisitorQueryExecutionContext context, QueryTreeTestUtils.QueryTreeNode root) {
    Assert.assertTrue("context has no root", context.getRoot().isPresent());
    QueryTreeTestUtils.test(context.getRoot().get(), root, Optional.empty());
  }

  static void assertNodesForQueries(QueryVisitorQueryExecutionContext context, Query... queries) {
    assertNodePresence(context, true, queries);
  }

  static void assertNoNodesForQueries(QueryVisitorQueryExecutionContext context, Query... queries) {
    assertNodePresence(context, false, queries);
  }

  private static void assertNodePresence(
      QueryVisitorQueryExecutionContext context, boolean shouldBePresent, Query... queries) {
    Assert.assertTrue(
        "root must exist before checking node presence", context.getRoot().isPresent());
    for (var query : queries) {
      Assert.assertEquals(
          String.format(
              "node %s missing for query: \"%s\"", shouldBePresent ? "is" : "should be", query),
          shouldBePresent,
          context.getOrCreateNode(query).isPresent());
    }
  }

  static Query termFor(String term) {
    return new TermQuery(new Term("foo", term));
  }
}
