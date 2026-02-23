load("//bazel/java:test.bzl", "java_test_suite")

java_test_suite(
    name = "util",
    srcs = [
        "BsonDocumentUtilTest.java",
        "RandomSegmentingIndexWriterTest.java",
    ],
    tags = ["unit"],
    deps = [
        "//src/main/java/com/xgen/testing/util",
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
        "@maven//:org_apache_lucene_lucene_core",
        "@maven//:org_mockito_mockito_core",
    ],
)
