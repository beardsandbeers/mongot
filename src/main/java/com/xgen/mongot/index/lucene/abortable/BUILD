load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "abortable",
    srcs = [
        "AbortableDirectory.java",
    ],
    visibility = ["//:__subpackages__"],
    exports = ["@maven//:org_apache_lucene_lucene_core"],
    deps = [
        "@maven//:org_apache_lucene_lucene_core",
        "@maven//:org_slf4j_slf4j_api",
    ],
)
