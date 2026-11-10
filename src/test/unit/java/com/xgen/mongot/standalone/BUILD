load("//bazel/java:test.bzl", "mongot_java_unit_test_suite")

mongot_java_unit_test_suite(
    name = "lib",
    sizes = {"MongotStandaloneTest.java": "medium"},
    timeouts = {"MongotStandaloneTest.java": "moderate"},
    deps = [
        "//src/main/java/com/xgen/mongot/standalone",
        "//src/main/java/com/xgen/mongot/standalone:bson",
        "//src/main/java/com/xgen/mongot/standalone/catalog",
        "//src/proto/standalone/v1:standalone_java_grpc",
        "//src/proto/standalone/v1:standalone_java_proto",
        "@com_google_protobuf//:protobuf_java",
        "@io_grpc_grpc_java//api",
        "@io_grpc_grpc_java//netty",
        "@maven//:org_mongodb_bson",
    ],
)
