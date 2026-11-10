load("//bazel/java:test.bzl", "mongot_java_unit_test_suite")

mongot_java_unit_test_suite(
    name = "lib",
    deps = [
        "//src/main/java/com/xgen/mongot/index/lucene/vector",
        "//src/main/java/com/xgen/mongot/standalone/catalog",
        "//src/main/java/com/xgen/mongot/standalone/config",
        "//src/main/java/com/xgen/mongot/standalone/runtime",
        "//src/main/java/com/xgen/mongot/standalone/server",
        "//src/proto/standalone/v1:standalone_java_grpc",
        "//src/proto/standalone/v1:standalone_java_proto",
        "@com_google_protobuf//:protobuf_java",
        "@io_grpc_grpc_java//api",
        "@io_grpc_grpc_java//netty",
        "@io_grpc_grpc_java//stub",
        "@maven//:io_netty_netty_handler",
        "@maven//:org_bouncycastle_bc_fips",
        "@maven//:org_bouncycastle_bcpkix_fips",
        "@maven//:org_mongodb_bson",
    ],
)
