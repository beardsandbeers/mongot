load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "context",
    srcs = ["IndexingPolicyBuilderContext.java"],
    visibility = ["//visibility:public"],
    exports = [
        "//src/main/java/com/xgen/mongot/util",
        "//src/main/java/com/xgen/mongot/util/bson",
    ],
    deps = [
        "//src/main/java/com/xgen/mongot/util",
        "//src/main/java/com/xgen/mongot/util/bson",
        "@maven//:com_google_guava_guava",
    ],
)
