load("//bazel/java:package.bzl", "java_library")

java_library(
    name = "definition",
    testonly = True,
    srcs = [
        "ManageSearchIndexCommandDefinitionBuilder.java",
        "UserSearchIndexDefinitionBuilder.java",
        "UserVectorIndexDefinitionBuilder.java",
    ],
    visibility = ["//visibility:public"],
    exports = [
        "//src/main/java/com/xgen/mongot/index/definition",
        "//src/main/java/com/xgen/mongot/server/command/management/definition",
        "//src/main/java/com/xgen/mongot/server/command/management/definition/common",
    ],
    deps = [
        "//src/main/java/com/xgen/mongot/index/definition",
        "//src/main/java/com/xgen/mongot/index/definition/quantization",
        "//src/main/java/com/xgen/mongot/index/query/sort",
        "//src/main/java/com/xgen/mongot/server/command/management/definition",
        "//src/main/java/com/xgen/mongot/server/command/management/definition/common",
        "//src/main/java/com/xgen/mongot/util",
        "//src/main/java/com/xgen/testing/mongot/index/definition",
        "@maven//:org_mongodb_bson",
    ],
)
