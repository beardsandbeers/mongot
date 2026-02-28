load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "lucene",
    testonly = True,
    srcs = [
        "LuceneConfigBuilder.java",
        "LuceneIndexMetricsSupplierBuilder.java",
        "LuceneIndexUtils.java",
    ],
    visibility = ["//visibility:public"],
    exports = [
        "//src/main/java/com/xgen/mongot/index/lucene",
        "//src/main/java/com/xgen/mongot/index/lucene/config",
    ],
    deps = [
        "//src/main/java/com/xgen/mongot/config/util",
        "//src/main/java/com/xgen/mongot/featureflag/dynamic",
        "//src/main/java/com/xgen/mongot/index",
        "//src/main/java/com/xgen/mongot/index/analyzer/definition",
        "//src/main/java/com/xgen/mongot/index/definition",
        "//src/main/java/com/xgen/mongot/index/lucene",
        "//src/main/java/com/xgen/mongot/index/lucene/backing",
        "//src/main/java/com/xgen/mongot/index/lucene/config",
        "//src/main/java/com/xgen/mongot/index/lucene/util",
        "//src/main/java/com/xgen/mongot/index/lucene/writer",
        "//src/main/java/com/xgen/mongot/index/status",
        "//src/main/java/com/xgen/mongot/metrics",
        "//src/main/java/com/xgen/mongot/util",
        "//src/main/java/com/xgen/testing",
        "//src/main/java/com/xgen/testing/mongot/index/version",
        "@maven//:io_micrometer_micrometer_core",
        "@maven//:junit_junit",
        "@maven//:org_mockito_mockito_core",
        "@maven//:org_mongodb_bson",
    ],
)
