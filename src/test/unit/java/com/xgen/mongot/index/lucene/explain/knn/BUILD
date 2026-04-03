load("//bazel/java:test.bzl", "java_test_suite")

java_test_suite(
    name = "knn",
    srcs = [
        "KnnInstrumentationHelperTest.java",
        "ReachabilityCheckerTest.java",
        "VectorSearchExplainerTest.java",
    ],
    tags = ["unit"],
    runtime_deps = [
        "@maven//:com_google_flogger_flogger_slf4j_backend",  # keep
    ],
    deps = [
        "//src/main/java/com/xgen/mongot/featureflag",
        "//src/main/java/com/xgen/mongot/index/definition",
        "//src/main/java/com/xgen/mongot/index/definition/quantization",
        "//src/main/java/com/xgen/mongot/index/lucene",
        "//src/main/java/com/xgen/mongot/index/lucene/codec",
        "//src/main/java/com/xgen/mongot/index/lucene/explain/information",
        "//src/main/java/com/xgen/mongot/index/lucene/explain/knn",
        "//src/main/java/com/xgen/mongot/index/lucene/explain/timing",
        "//src/main/java/com/xgen/mongot/index/lucene/explain/tracing",
        "//src/main/java/com/xgen/mongot/index/lucene/field",
        "//src/main/java/com/xgen/mongot/index/lucene/searcher",
        "//src/main/java/com/xgen/mongot/util",
        "//src/main/java/com/xgen/mongot/util/timers",
        "//src/main/java/com/xgen/testing/mongot/mock/index",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
        "@maven//:org_apache_lucene_lucene_core",
        "@maven//:org_mockito_mockito_core",
        "@maven//:org_mongodb_bson",
    ],
)
