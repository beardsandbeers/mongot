#!/usr/bin/env python3
"""Build the Linux x86_64 server and independently installable clients; never uploads."""

import argparse
import hashlib
import json
import os
import platform
import shlex
import shutil
import subprocess
import sys
import tomllib
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def run(command, *, cwd=ROOT, capture=False):
    command = [str(part) for part in command]
    print("+ " + shlex.join(command), flush=True)
    result = subprocess.run(
        command, cwd=cwd, check=True, text=True, stdout=subprocess.PIPE if capture else None
    )
    return result.stdout if capture else None


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def seal(output):
    files = sorted(p for p in output.iterdir() if p.is_file() and p.name != "SHA256SUMS")
    (output / "SHA256SUMS").write_text("".join(f"{sha256(p)}  {p.name}\n" for p in files))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist/standalone")
    parser.add_argument("--maven", default=os.environ.get("MONGOT_MVN", "mvn"))
    parser.add_argument("--skip-server-build", action="store_true")
    parser.add_argument(
        "--seal-only", action="store_true", help="Refresh checksums after adding test reports"
    )
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if args.seal_only:
        seal(output)
        return
    version = json.loads((ROOT / "clients/node/package.json").read_text())["version"]
    for path, key in [
        ("clients/python/pyproject.toml", "project"),
        ("clients/rust/Cargo.toml", "package"),
    ]:
        if tomllib.loads((ROOT / path).read_text())[key]["version"] != version:
            parser.error("Client package versions differ")
    commit = run(["git", "rev-parse", "HEAD"], capture=True).strip()
    run([sys.executable, "clients/sync_proto.py", "--check"])
    for package in ("python", "node", "rust"):
        if (ROOT / "LICENSE").read_bytes() != (ROOT / "clients" / package / "LICENSE").read_bytes():
            parser.error(f"{package} package license is stale")
    if not args.skip_server_build:
        run(
            [
                "bazel",
                "build",
                "--platforms=//bazel/platforms:linux_x86_64",
                f"--//bazel/config:version={version}",
                f"--embed_label=standalone-v{version}",
                "//deploy:mongot-standalone",
            ]
        )
    shutil.copyfile(
        ROOT / "bazel-bin/deploy/mongot-standalone.tgz",
        output / f"mongot-standalone-{version}-linux-x86_64.tgz",
    )
    run([sys.executable, "clients/python/generate.py"])
    run([sys.executable, "-m", "build", "--outdir", output, ROOT / "clients/python"])
    run(
        [
            args.maven,
            "-B",
            "-ntp",
            "-f",
            "clients/java/pom.xml",
            "package",
            "org.apache.maven.plugins:maven-source-plugin:3.3.1:jar-no-fork",
        ]
    )
    with zipfile.ZipFile(
        output / f"mongot-standalone-client-java-{version}.zip",
        "w",
        compression=zipfile.ZIP_DEFLATED,
    ) as archive:
        prefix = f"mongot-standalone-client-java-{version}/"
        for source, name in [
            (
                ROOT / f"clients/java/target/standalone-client-{version}.jar",
                f"standalone-client-{version}.jar",
            ),
            (
                ROOT / f"clients/java/target/standalone-client-{version}-sources.jar",
                f"standalone-client-{version}-sources.jar",
            ),
            (ROOT / "clients/java/pom.xml", "pom.xml"),
            (ROOT / "LICENSE", "LICENSE"),
        ]:
            archive.write(source, prefix + name)
        archive.writestr(
            prefix + "README.md",
            f"""# Java standalone mongot client {version}

Java 17+ is required. Use standalone-client-{version}.jar on your application's classpath;
it includes the client, generated messages and runtime dependencies. Import
com.xgen.mongot.client.StandaloneClient and com.xgen.mongot.standalone.grpc messages.

Run the JSON-lines adapter with: java -jar standalone-client-{version}.jar
Set MONGOT_ADDRESS, MONGOT_CA and MONGOT_TOKEN_FILE. Omit CA/token for plaintext development.
Sources and the Maven POM are included. To install in your local Maven repository:

    mvn install:install-file -Dfile=standalone-client-{version}.jar -DpomFile=pom.xml

API examples: https://github.com/beardsandbeers/nomongo-t/blob/{commit}/clients/README.md
""",
        )
    run(["npm", "ci", "--no-audit", "--no-fund"], cwd=ROOT / "clients/node")
    run(["npm", "run", "build"], cwd=ROOT / "clients/node")
    run(["npm", "pack", "--pack-destination", output], cwd=ROOT / "clients/node")
    run(
        [
            "cargo",
            "package",
            "--manifest-path",
            "clients/rust/Cargo.toml",
            "--locked",
            "--allow-dirty",
        ]
    )
    shutil.copyfile(
        ROOT / f"clients/rust/target/package/mongot-standalone-client-{version}.crate",
        output / f"mongot-standalone-client-{version}.crate",
    )
    shutil.copyfile(ROOT / "src/proto/standalone/v1/standalone.proto", output / "standalone.proto")
    shutil.copyfile(ROOT / "LICENSE", output / "LICENSE")
    instructions = (ROOT / "clients/release-install.md.in").read_text()
    (output / "INSTALL.md").write_text(
        instructions.replace("@VERSION@", version).replace("@COMMIT@", commit)
    )
    artifacts = sorted(
        p
        for p in output.iterdir()
        if p.is_file()
        and p.name not in ("SHA256SUMS", "release-manifest.json")
        and not p.name.startswith("verification-")
    )
    manifest = {
        "version": version,
        "tag": f"standalone-v{version}",
        "sourceCommit": commit,
        "workingTreeChanges": run(
            ["git", "diff", "--name-only", "HEAD"], capture=True
        ).splitlines(),
        "builtAt": datetime.now(timezone.utc).isoformat(),
        "serverPlatform": "linux-x86_64",
        "buildHost": platform.platform(),
        "schemaSha256": sha256(ROOT / "src/proto/standalone/v1/standalone.proto"),
        "artifacts": [
            {"name": p.name, "bytes": p.stat().st_size, "sha256": sha256(p)} for p in artifacts
        ],
    }
    (output / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    seal(output)
    print(f"Release assets: {output}", flush=True)


if __name__ == "__main__":
    main()
