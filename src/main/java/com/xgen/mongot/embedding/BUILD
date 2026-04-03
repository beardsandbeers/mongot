load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "embedding",
    srcs = [
        "AutoEmbeddingMemoryBudget.java",
        "EmbeddingRequestContext.java",
        "MongotMetadata.java",
        "VectorOrError.java",
    ],
    visibility = ["//:__subpackages__"],
    deps = [
        "//src/main/java/com/xgen/mongot/index/definition/quantization",
        "//src/main/java/com/xgen/mongot/util",
        "//src/main/java/com/xgen/mongot/util/bson",
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:com_google_flogger_flogger",
    ],
)
