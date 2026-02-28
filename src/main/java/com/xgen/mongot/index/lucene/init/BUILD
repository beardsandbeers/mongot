load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "init",
    srcs = ["LuceneIndexResourcesInitializer.java"],
    visibility = ["//visibility:public"],
    exports = [
        "//src/main/java/com/xgen/mongot/index/lucene/searcher",
        "//src/main/java/com/xgen/mongot/index/lucene/writer",
    ],
    deps = [
        "//src/main/java/com/xgen/mongot/index",
        "//src/main/java/com/xgen/mongot/index/lucene/directory",
        "//src/main/java/com/xgen/mongot/index/lucene/searcher",
        "//src/main/java/com/xgen/mongot/index/lucene/writer",
        "//src/main/java/com/xgen/mongot/util",
        "@maven//:com_google_guava_guava",
        "@maven//:org_apache_lucene_lucene_core",
    ],
)
