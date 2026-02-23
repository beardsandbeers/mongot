load("//bazel/java:test.bzl", "java_test_suite")

java_test_suite(
    name = "codec",
    # Note: LuceneStoredFieldsFormatTest needs "moderate" timeout, but java_test_suite
    # applies timeout uniformly, so setting moderate for the suite.
    timeout = "moderate",
    srcs = [
        "LuceneCodecTest.java",
        "LuceneStoredFieldsFormatTest.java",
    ],
    tags = ["unit"],
    deps = [
        "//src/main/java/com/xgen/mongot/index/definition",
        "//src/main/java/com/xgen/mongot/index/lucene/codec",
        "//src/main/java/com/xgen/mongot/index/lucene/codec/flat",
        "//src/main/java/com/xgen/mongot/index/lucene/field",
        "//src/main/java/com/xgen/mongot/index/lucene/quantization",
        "//src/main/java/com/xgen/mongot/index/lucene/util",
        "//src/main/java/com/xgen/mongot/util",
        "@maven//:com_google_flogger_flogger_system_backend",
        "@maven//:junit_junit",
        "@maven//:org_apache_lucene_lucene_core",
        "@maven//:org_apache_lucene_lucene_test_framework",
    ],
)
