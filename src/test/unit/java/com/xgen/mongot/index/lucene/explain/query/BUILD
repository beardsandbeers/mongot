load("//bazel/java:test.bzl", "java_test_suite")

java_test_suite(
    name = "query",
    srcs = [
        "ExplainQueryExecutionContextTest.java",
        "ExplainVisitorTest.java",
        "QueryTreeTestUtils.java",
        "RewrittenChildClausesTest.java",
        "RewrittenQueryExecutionContextNodeTest.java",
    ],
    tags = ["unit"],
    deps = [
        "//src/main/java/com/xgen/mongot/featureflag",
        "//src/main/java/com/xgen/mongot/index",
        "//src/main/java/com/xgen/mongot/index/lucene/explain/query",
        "//src/main/java/com/xgen/mongot/index/lucene/explain/timing",
        "//src/main/java/com/xgen/mongot/index/lucene/query/custom",
        "//src/main/java/com/xgen/mongot/index/lucene/util",
        "//src/main/java/com/xgen/testing/mongot/index/lucene/explain/query",
        "//src/main/java/com/xgen/testing/mongot/index/lucene/explain/timing",
        "//src/main/java/com/xgen/testing/mongot/mock/index",
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
        "@maven//:org_apache_lucene_lucene_core",
    ],
)
