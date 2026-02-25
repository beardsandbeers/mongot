load("//bazel/java:test.bzl", "java_test_suite")

java_test_suite(
    name = "autoembedding",
    srcs = [
        "AutoEmbeddingCompositeIndexTest.java",
        "MaterializedViewGenerationIdTest.java",
    ],
    tags = ["unit"],
    deps = [
        "//src/main/java/com/xgen/mongot/index",
        "//src/main/java/com/xgen/mongot/index/autoembedding",
        "//src/main/java/com/xgen/mongot/index/status",
        "//src/main/java/com/xgen/mongot/index/version",
        "//src/main/java/com/xgen/mongot/replication/mongodb/common",
        "//src/main/java/com/xgen/mongot/util/functionalinterfaces",
        "//src/main/java/com/xgen/testing/mongot/index/definition",
        "//src/main/java/com/xgen/testing/mongot/index/version",
        "//src/main/java/com/xgen/testing/mongot/replication/mongodb",
        "@maven//:com_google_guava_guava",
        "@maven//:junit_junit",
        "@maven//:org_mockito_mockito_core",
        "@maven//:org_mongodb_bson",
        "@maven//:org_mongodb_mongodb_driver_core",
    ],
)
