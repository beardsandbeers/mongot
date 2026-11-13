#!/usr/bin/env python3
"""Install release assets outside the checkout and test every RPC through those installations."""

import argparse
import json
import os
import shlex
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path

from package_release import ROOT, seal, sha256


def run(command, *, cwd=ROOT, env=None):
    command = [str(part) for part in command]
    print("+ " + shlex.join(command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def extract(archive, target):
    with tarfile.open(archive, "r:gz") as stream:
        stream.extractall(target, filter="data")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist/standalone")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    manifest = json.loads((output / "release-manifest.json").read_text())
    version = manifest["version"]
    for artifact in manifest["artifacts"]:
        path = output / artifact["name"]
        if path.stat().st_size != artifact["bytes"] or sha256(path) != artifact["sha256"]:
            parser.error(f"Artifact differs from manifest: {path.name}")
    work = Path(tempfile.mkdtemp(prefix="mongot-release-verify-"))
    print(f"Isolated package installations: {work}", flush=True)
    extract(output / f"mongot-standalone-{version}-linux-x86_64.tgz", work / "server")
    launcher = work / "server/mongot-standalone/mongot-standalone"
    run([sys.executable, "-m", "venv", work / "python"])
    python = work / "python/bin/python"
    wheel = output / f"mongot_standalone_client-{version}-py3-none-any.whl"
    run([python, "-m", "pip", "install", "--disable-pip-version-check", wheel], cwd=work)
    run([python, "-c", "import mongot_standalone; print(mongot_standalone.__file__)"], cwd=work)
    node = work / "node"
    node.mkdir()
    (node / "package.json").write_text(
        '{"name":"release-consumer","version":"1.0.0","private":true}\n'
    )
    run(
        [
            "npm",
            "install",
            "--no-audit",
            "--no-fund",
            output / f"mongot-standalone-client-{version}.tgz",
            "@types/node@22.19.0",
        ],
        cwd=node,
    )
    (
        node / "consumer.ts"
    ).write_text("""import { StandaloneClient, encodeBson, decodeBson } from '@mongot/standalone-client';
const client = new StandaloneClient();
const status: Promise<{instanceId: string}> = client.getStatus({});
client.createIndex({requestId: 'r', collectionId: 'c', name: 'text', type: 'INDEX_TYPE_SEARCH',
    definitionBson: encodeBson({mappings: {dynamic: true}})});
decodeBson(encodeBson({big: 1}));
client.close();
""")
    run(
        [
            "node",
            ROOT / "clients/node/node_modules/typescript/bin/tsc",
            "--strict",
            "--noEmit",
            "--target",
            "ES2022",
            "--module",
            "commonjs",
            "--moduleResolution",
            "node",
            "--skipLibCheck",
            "consumer.ts",
        ],
        cwd=node,
    )
    extract(output / f"mongot-standalone-client-{version}.crate", work / "rust")
    rust = work / f"rust/mongot-standalone-client-{version}"
    rust_env = dict(
        os.environ,
        CARGO_TARGET_DIR=str(work / "rust-target"),
        CARGO_PROFILE_DEV_DEBUG="0",
        CARGO_PROFILE_TEST_DEBUG="0",
    )
    run(["cargo", "build", "--locked"], cwd=rust, env=rust_env)
    run(["cargo", "test", "--locked", "--lib"], cwd=rust, env=rust_env)
    java = work / "java"
    with zipfile.ZipFile(output / f"mongot-standalone-client-java-{version}.zip") as archive:
        archive.extractall(java)
    java = java / f"mongot-standalone-client-java-{version}"
    jar = java / f"standalone-client-{version}.jar"
    (java / "Consumer.java").write_text("""import com.xgen.mongot.client.StandaloneClient;
import com.xgen.mongot.standalone.grpc.CreateCollectionRequest;
import org.bson.BsonDocument;
class Consumer {
  public static void main(String[] args) {
    var document = BsonDocument.parse("{big:{$numberLong:'1152921504606846977'}}");
    var bytes = StandaloneClient.encodeBson(document);
    if (!StandaloneClient.encodeBson(StandaloneClient.decodeBson(bytes)).equals(bytes))
      throw new AssertionError("BSON changed");
    CreateCollectionRequest.newBuilder().setDatabase("example").setName("test").build();
  }
}
""")
    run(["javac", "-cp", jar, java / "Consumer.java"], cwd=java)
    run(["java", "-cp", str(jar) + os.pathsep + str(java), "Consumer"], cwd=java)
    with zipfile.ZipFile(java / f"standalone-client-{version}-sources.jar") as archive:
        if "com/xgen/mongot/client/StandaloneClient.java" not in archive.namelist():
            parser.error("Java sources package is incomplete")
    environment = dict(
        os.environ,
        MONGOT_PYTHON_COMMAND=shlex.join([str(python), "-m", "mongot_standalone.cli"]),
        MONGOT_JAVA_COMMAND=shlex.join(["java", "-jar", str(jar)]),
        MONGOT_NODE_COMMAND=shlex.join(
            ["node", str(node / "node_modules/@mongot/standalone-client/dist/cli.js")]
        ),
        MONGOT_RUST_COMMAND=str(work / "rust-target/debug/mongot-standalone-client"),
    )
    for mode in ("tls", "plaintext"):
        if mode == "plaintext":
            # Verify the sdist is independently buildable/installable as well as the wheel.
            run(
                [
                    python,
                    "-m",
                    "pip",
                    "install",
                    "--disable-pip-version-check",
                    "--force-reinstall",
                    "--no-deps",
                    output / f"mongot_standalone_client-{version}.tar.gz",
                ],
                cwd=work,
            )
        report_path = output / f"verification-{mode}.json"
        command = [
            python,
            ROOT / "clients/tests/e2e.py",
            "--launcher",
            launcher,
            "--output",
            report_path,
        ]
        if mode == "plaintext":
            command.append("--plaintext")
        run(command, cwd=work, env=environment)
        report = json.loads(report_path.read_text())
        if not report["passed"] or len(report["results"]) != 4:
            parser.error(f"Packaged {mode} integration tests failed")
        report["sourceCommit"] = manifest["sourceCommit"]
        report["artifactSha256"] = {item["name"]: item["sha256"] for item in manifest["artifacts"]}
        report["installationsOutsideCheckout"] = True
        report_path.write_text(json.dumps(report, indent=2) + "\n")
    seal(output)
    print(
        "PASS: all release packages install and run independently; TLS and plaintext E2E passed",
        flush=True,
    )


if __name__ == "__main__":
    main()
