load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "statistics",
    srcs = [
        "IndexStatistics.java",
        "LuceneFieldRanges.java",
        "LuceneStatisticsCollector.java",
        "LuceneStorageAttribution.java",
        "StatisticsLimitException.java",
        "StorageLedger.java",
    ],
    visibility = ["//visibility:public"],
    exports = [":snapshot"],
    deps = [
        ":snapshot",
        "//src/main/java/com/xgen/mongot/index/definition",
        "//src/main/java/com/xgen/mongot/index/lucene/codec/bloom",
        "//src/main/java/com/xgen/mongot/index/lucene/field",
        "//src/main/java/com/xgen/mongot/index/version",
        "//src/main/java/com/xgen/mongot/util",
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:org_apache_lucene_lucene_core",
    ],
)

java_library(
    name = "snapshot",
    srcs = ["LuceneStatisticsSnapshot.java"],
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:org_apache_lucene_lucene_core",
    ],
)
