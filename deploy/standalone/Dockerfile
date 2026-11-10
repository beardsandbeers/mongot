# syntax=docker/dockerfile:1
ARG BUILD_IMAGE=ubuntu:22.04
ARG RUNTIME_IMAGE=ubuntu:24.04
ARG PACKAGE_SOURCE=source-package

# Build everything inside Docker; the host needs no Java, Bazel, or compiler.
FROM ${BUILD_IMAGE} AS source-package
ARG TARGETARCH
ARG BUILD_JOBS=2
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    build-essential ca-certificates curl git libtinfo5 openjdk-21-jdk-headless \
    python3 unzip zip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .bazelignore .bazelrc .bazelversion BUILD MODULE.bazel LICENSE ./
COPY bazel/ bazel/
COPY conf/ conf/
COPY docs/ docs/
COPY packages/ packages/
COPY scripts/ scripts/
COPY src/ src/
COPY tools/ tools/
COPY deploy/BUILD deploy/def.bzl deploy/bc-fips-env.sh deploy/
COPY deploy/community-resources/ deploy/community-resources/
COPY deploy/standalone-resources/ deploy/standalone-resources/
RUN --mount=type=cache,id=mongot-standalone-build-${TARGETARCH},target=/root/.cache,sharing=locked \
    case "${TARGETARCH}" in \
      amd64) platform=linux_x86_64 ;; \
      arm64) platform=linux_aarch64 ;; \
      *) echo "Unsupported build architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac && \
    scripts/tools/bazelisk/run.sh --batch --host_jvm_args=-Xmx1536m build \
      --jobs="${BUILD_JOBS}" --local_resources=memory=4096 \
      --extra_toolchains=@@rules_cc++cc_configure_extension+local_config_cc_toolchains//:all \
      --platforms="//bazel/platforms:${platform}" //deploy:mongot-standalone && \
    cp -L bazel-bin/deploy/mongot-standalone.tgz /mongot-standalone.tgz

# Optional fast path: package a tarball already built on a matching Linux architecture.
FROM scratch AS local-package
COPY deploy/standalone/.package/mongot-standalone.tgz /mongot-standalone.tgz

FROM ${PACKAGE_SOURCE} AS package

FROM ${RUNTIME_IMAGE} AS runtime
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates libstdc++6 openssl && rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 mongot && \
    useradd --uid 10001 --gid 10001 --no-create-home --home-dir /var/lib/mongot mongot && \
    mkdir -p /opt/mongot /var/lib/mongot && chown 10001:10001 /var/lib/mongot
RUN --mount=type=bind,from=package,source=/mongot-standalone.tgz,target=/tmp/mongot-standalone.tgz \
    tar -xzf /tmp/mongot-standalone.tgz --strip-components=1 -C /opt/mongot
COPY deploy/standalone/StandaloneControl.java /opt/mongot/tools/StandaloneControl.java
RUN /opt/mongot/bin/jdk/bin/javac \
    -cp '/opt/mongot/lib/*:/opt/mongot/bin/mongot_standalone_deploy.jar' \
    /opt/mongot/tools/StandaloneControl.java
COPY --chmod=755 deploy/standalone/entrypoint.sh /usr/local/bin/mongot-entrypoint
COPY --chmod=755 deploy/standalone/mongotctl /usr/local/bin/mongotctl
USER 10001:10001
WORKDIR /var/lib/mongot
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx2g -XX:MaxDirectMemorySize=512m"
VOLUME ["/var/lib/mongot"]
EXPOSE 27029
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD ["mongotctl", "health"]
ENTRYPOINT ["mongot-entrypoint"]
