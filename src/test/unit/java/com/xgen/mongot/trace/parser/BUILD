load("//bazel/java:test.bzl", "java_test_suite")

# gazelle:resolve java com.xgen.mongot.trace.parser //src/main/java/com/xgen/mongot/trace/parser:lib

java_test_suite(
    name = "parser",
    srcs = ["JsonConvertorTest.java"],
    data = ["//src/test/unit/resources/trace/parser:srcs"],
    tags = ["unit"],
    deps = [
        "//src/main/java/com/xgen/mongot/trace/parser:lib",
        "@maven//:com_googlecode_json_simple_json_simple",
        "@maven//:junit_junit",
    ],
)
