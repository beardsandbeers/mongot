load("@rules_java//java:defs.bzl", "java_binary")
load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "bson",
    srcs = ["StandaloneBson.java"],
    deps = [
        "//src/main/java/com/xgen/mongot/index/lucene/util",
        "//src/proto/standalone/v1:standalone_java_proto",
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:org_apache_lucene_lucene_core",
        "@maven//:org_mongodb_bson",
    ],
)

java_library(
    name = "standalone",
    srcs = ["MongotStandalone.java"],
    deps = [
        "//src/main/java/com/xgen/mongot/index/lucene/vector",
        "//src/main/java/com/xgen/mongot/logging",
        "//src/main/java/com/xgen/mongot/standalone/config",
        "//src/main/java/com/xgen/mongot/standalone/runtime",
        "//src/main/java/com/xgen/mongot/standalone/server",
        "//src/main/java/com/xgen/mongot/util/security",
        "@maven//:info_picocli_picocli",
        "@maven//:org_slf4j_slf4j_api",
    ],
)

java_binary(
    name = "MongotStandalone",
    jvm_flags = ["--enable-preview"],
    main_class = "com.xgen.mongot.standalone.MongotStandalone",
    visibility = ["//visibility:public"],
    runtime_deps = [":standalone"],
)
