load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "ingestion",
    srcs = [
        "ApiIngestionManager.java",
        "DurableWriteLedger.java",
        "GenerationConsumer.java",
        "StandaloneDocumentIndexer.java",
        "StandaloneIndexCommitUserData.java",
    ],
    deps = [
        "//src/main/java/com/xgen/mongot/catalog",
        "//src/main/java/com/xgen/mongot/index",
        "//src/main/java/com/xgen/mongot/index/lucene",
        "//src/main/java/com/xgen/mongot/index/lucene/util",
        "//src/main/java/com/xgen/mongot/index/status",
        "//src/main/java/com/xgen/mongot/index/version",
        "//src/main/java/com/xgen/mongot/replication",
        "//src/main/java/com/xgen/mongot/standalone:bson",
        "//src/main/java/com/xgen/mongot/standalone/catalog",
        "//src/main/java/com/xgen/mongot/standalone/metrics",
        "//src/main/java/com/xgen/mongot/standalone/store",
        "//src/main/java/com/xgen/mongot/util/mongodb",
        "//src/main/java/com/xgen/mongot/util/regex",
        "//src/proto/standalone/v1:standalone_java_proto",
        "@com_google_protobuf//:protobuf_java",
        "@io_grpc_grpc_java//api",
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:org_mongodb_bson",
    ],
)
