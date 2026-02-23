load("//bazel/java:test.bzl", "java_test_suite")

java_test_suite(
    name = "testing",
    srcs = [
        "BsonDeserializationTestSuiteTest.java",
        "ControlledBlockingQueueTest.java",
        "ControlledPriorityBlockingQueueTest.java",
        "Java11.java",
        "Java21.java",
        "LuceneIndexRuleTest.java",
        "LuceneTestUtilsTest.java",
        "PanamaVectorUtilSupportTest.java",
    ],
    data = ["//src/test/unit/resources:srcs"],
    tags = ["unit"],
    deps = [
        "//src/main/java/com/xgen/testing",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
        "@maven//:org_mockito_mockito_core",
    ],
)
