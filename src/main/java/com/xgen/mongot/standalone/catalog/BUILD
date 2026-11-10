load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "catalog",
    srcs = [
        "LocalAuthoritativeCatalog.java",
        "StandaloneDefinitionValidator.java",
    ],
    deps = [
        "//src/main/java/com/xgen/mongot/config/util",
        "//src/main/java/com/xgen/mongot/index/analyzer",
        "//src/main/java/com/xgen/mongot/index/definition",
        "//src/main/java/com/xgen/mongot/index/version",
        "//src/main/java/com/xgen/mongot/server/command/management/definition/common",
        "//src/main/java/com/xgen/mongot/server/command/management/util",
        "//src/main/java/com/xgen/mongot/standalone:bson",
        "//src/main/java/com/xgen/mongot/standalone/store",
        "//src/main/java/com/xgen/mongot/util/bson/parser",
        "//src/proto/standalone/v1:standalone_java_proto",
        "@com_google_protobuf//:protobuf_java",
        "@io_grpc_grpc_java//api",
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:org_mongodb_bson",
    ],
)
