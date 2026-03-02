load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "backing",
    srcs = [
        "DiskStats.java",
        "IndexBackingStrategy.java",
    ],
    visibility = ["//visibility:public"],
    deps = [
        "//src/main/java/com/xgen/mongot/index/status",
        "@maven//:com_google_guava_guava",
        "@maven//:org_apache_lucene_lucene_core",
    ],
)
