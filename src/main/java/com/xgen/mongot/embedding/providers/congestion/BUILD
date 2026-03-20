load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "congestion",
    srcs = [
        "AimdCongestionControl.java",
        "DynamicSemaphore.java",
        "DynamicSemaphorePolicy.java",
    ],
    visibility = ["//:__subpackages__"],
    exports = ["@maven//:org_mongodb_bson"],
    deps = [
        "//src/main/java/com/xgen/mongot/util/bson/parser",
        "@maven//:com_google_code_findbugs_jsr305",
        "@maven//:com_google_guava_guava",
        "@maven//:org_mongodb_bson",
        "@maven//:org_slf4j_slf4j_api",
    ],
)
