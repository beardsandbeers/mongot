load("//bazel/java:test.bzl", "java_test_suite")

java_test_suite(
    name = "init",
    srcs = ["LuceneIndexResourcesInitializerTest.java"],
    tags = ["unit"],
    deps = [
        "//src/main/java/com/xgen/mongot/index",
        "//src/main/java/com/xgen/mongot/index/lucene/commit",
        "//src/main/java/com/xgen/mongot/index/lucene/directory",
        "//src/main/java/com/xgen/mongot/index/lucene/init",
        "//src/main/java/com/xgen/mongot/index/lucene/searcher",
        "//src/main/java/com/xgen/mongot/index/lucene/writer",
        "//src/main/java/com/xgen/mongot/util",
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
        "@maven//:org_apache_lucene_lucene_core",
        "@maven//:org_mockito_mockito_core",
    ],
)
