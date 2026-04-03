load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "quantization",
    srcs = [
        "VectorAutoEmbedQuantization.java",
        "VectorQuantization.java",
    ],
    visibility = ["//visibility:public"],
    exports = ["//src/main/java/com/xgen/mongot/index/lucene/field"],
    deps = [
        "//src/main/java/com/xgen/mongot/index/lucene/field",
        "@maven//:com_google_guava_guava",
    ],
)
