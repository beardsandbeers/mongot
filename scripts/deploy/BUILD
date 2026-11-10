load("@rules_python//python:defs.bzl", "py_binary", "py_test")

py_binary(
    name = "apply_spi_service_overrides",
    srcs = ["apply_spi_service_overrides.py"],
    visibility = ["//visibility:public"],
)

py_test(
    name = "apply_spi_service_overrides_test",
    srcs = ["apply_spi_service_overrides_test.py"],
    tags = ["unit"],
    deps = [":apply_spi_service_overrides"],
)
