load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "config",
    srcs = ["StandaloneConfig.java"],
    deps = [
        "//src/main/java/com/xgen/mongot/standalone/catalog",
        "@maven//:com_fasterxml_jackson_core_jackson_core",
        "@maven//:com_fasterxml_jackson_core_jackson_databind",
        "@maven//:com_fasterxml_jackson_dataformat_jackson_dataformat_yaml",
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:com_google_guava_guava",
        "@maven//:org_jetbrains_annotations",
    ],
)
