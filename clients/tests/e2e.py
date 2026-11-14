#!/usr/bin/env python3
"""Exercise every RPC through each native client against a real standalone process.

Run with the Python client installed. Builds are explicit: this suite never downloads tools.
"""

import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import selectors
import shlex
import socket
import subprocess
import sys
import tempfile
import time
import uuid
from contextlib import ExitStack
from pathlib import Path

import bson
import grpc
from bson import Int64
from bson.json_util import loads
from mongot_standalone import Client, api

ROOT = Path(__file__).resolve().parents[2]
PROTO = ROOT / "src/proto/standalone/v1/standalone.proto"
CLIENT_VERSION = json.loads((ROOT / "clients/node/package.json").read_text())["version"]
RPC_NAMES = set(re.findall(r"\brpc (\w+)\(", PROTO.read_text()))


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def encoded(document):
    return base64.b64encode(bson.encode(document)).decode("ascii")


def decoded(value):
    return bson.decode(base64.b64decode(value))


class Driver:
    def __init__(self, language, environment, logs):
        self.language = language
        self.called = set()
        commands = {
            "python": [sys.executable, "-m", "mongot_standalone.cli"],
            "java": [
                "java",
                "-jar",
                str(ROOT / f"clients/java/target/standalone-client-{CLIENT_VERSION}.jar"),
            ],
            "node": ["node", str(ROOT / "clients/node/dist/cli.js")],
            "rust": [str(ROOT / "clients/rust/target/debug/mongot-standalone-client")],
        }
        override = os.environ.get("MONGOT_" + language.upper() + "_COMMAND")
        command = shlex.split(override) if override else commands[language]
        self.stderr = (logs / (language + "-" + uuid.uuid4().hex + ".log")).open("w")
        self.process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self.stderr,
            env=environment,
            text=True,
            bufsize=1,
        )
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)

    def call(self, method, request=None, error=None):
        check(method in RPC_NAMES, f"Unknown RPC {method}")
        self.called.add(method)
        self.process.stdin.write(json.dumps({"method": method, "request": request or {}}) + "\n")
        self.process.stdin.flush()
        if not self.selector.select(timeout=45):
            raise TimeoutError(
                f"{self.language} {method} produced no response; inspect {self.stderr.name}"
            )
        line = self.process.stdout.readline()
        check(
            bool(line),
            f"{self.language} exited ({self.process.poll()}); inspect {self.stderr.name}",
        )
        result = json.loads(line)
        if error:
            check(
                result.get("error", {}).get("code") == error,
                f"{self.language} {method}: expected {error}, got {result}",
            )
            return result["error"]
        check("response" in result, f"{self.language} {method}: {result}")
        return result["response"]

    def close(self):
        self.selector.close()
        self.process.stdin.close()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.process.stdout.close()
        self.stderr.close()
        check(
            self.process.returncode == 0, f"{self.language} exited with {self.process.returncode}"
        )

    def __enter__(self):
        return self

    def __exit__(self, *_):
        try:
            self.close()
        except Exception:
            if not _ or _[0] is None:
                raise


class Server:
    def __init__(self, launcher, directory, plaintext):
        self.launcher = str(Path(launcher).resolve())
        packaged_schema = Path(self.launcher).parent / "standalone.proto"
        if packaged_schema.exists():
            check(
                packaged_schema.read_bytes() == PROTO.read_bytes(),
                "Packaged server schema differs from the clients' schema",
            )
        self.directory = directory
        self.process = None
        self.log = None
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.address = f"127.0.0.1:{probe.getsockname()[1]}"
        self.environment = dict(os.environ, MONGOT_ADDRESS=self.address, MONGOT_TIMEOUT="20")
        for key in ("MONGOT_CA", "MONGOT_TOKEN_FILE"):
            self.environment.pop(key, None)
        transport = ""
        if not plaintext:
            ca = directory / "server.crt"
            key = directory / "server.key"
            token = directory / "admin.token"
            token.write_text(secrets.token_hex(32) + "\n")
            token.chmod(0o600)
            subprocess.run(
                [
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "rsa:2048",
                    "-nodes",
                    "-keyout",
                    str(key),
                    "-out",
                    str(ca),
                    "-days",
                    "2",
                    "-subj",
                    "/CN=localhost",
                    "-addext",
                    "subjectAltName=DNS:localhost,IP:127.0.0.1",
                    "-addext",
                    "basicConstraints=critical,CA:FALSE",
                    "-addext",
                    "keyUsage=critical,digitalSignature,keyEncipherment",
                    "-addext",
                    "extendedKeyUsage=serverAuth",
                ],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            self.environment.update(MONGOT_CA=str(ca), MONGOT_TOKEN_FILE=str(token))
            transport = f"""    tls:
      certificate: {json.dumps(str(ca))}
      privateKey: {json.dumps(str(key))}
    principals:
      - name: admin
        tokenFile: {json.dumps(str(token))}
        roles: [catalog, write, query]
        indexes: ['*']
"""
        self.config = directory / "standalone.yaml"
        self.config.write_text(f"""mode: standalone
storage:
  dataPath: {json.dumps(str(directory / "data"))}
server:
  grpc:
    address: {self.address}
{transport}ingestion:
  commitIntervalMillis: 100
querying:
  refreshIntervalMillis: 100
""")

    def start(self):
        self.log = (self.directory / ("server-" + uuid.uuid4().hex + ".log")).open("w")
        environment = dict(os.environ, JAVA_TOOL_OPTIONS="-Xms128m -Xmx2g")
        self.process = subprocess.Popen(
            [self.launcher, "--config", str(self.config)],
            stdout=self.log,
            stderr=subprocess.STDOUT,
            env=environment,
        )
        deadline = time.monotonic() + 90
        with Client(
            self.address,
            ca_file=self.environment.get("MONGOT_CA"),
            token_file=self.environment.get("MONGOT_TOKEN_FILE"),
            timeout=2,
        ) as client:
            while time.monotonic() < deadline:
                check(self.process.poll() is None, f"Server exited; inspect {self.log.name}")
                try:
                    if client.get_status(api.GetStatusRequest()).management_ready:
                        return
                except grpc.RpcError:
                    pass
                time.sleep(0.1)
        raise TimeoutError(f"Server did not become ready; inspect {self.log.name}")

    def stop(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
                raise TimeoutError("Server failed to shut down cleanly")
        if self.log:
            self.log.close()

    def __enter__(self):
        try:
            self.start()
        except BaseException:
            self.stop()
            raise
        return self

    def __exit__(self, *_):
        self.stop()


def ready(client, index_id, revision=1):
    until = time.monotonic() + 60
    while time.monotonic() < until:
        index = client.call("GetIndex", {"indexId": index_id})
        check(not index.get("failureMessage"), f"Index failed: {index}")
        if (
            index.get("lifecycleState") == "READY"
            and int(index.get("activeRevision", 0)) == revision
        ):
            return index
        time.sleep(0.05)
    raise TimeoutError(f"Index {index_id} did not reach revision {revision}")


def batch_results(response):
    return decoded(response["batchBson"])["results"]


def search(client, index, term, path="title", *, stored=False, size=100, generation=None):
    expression = {"text": {"path": path, "query": term}}
    if stored:
        expression["returnStoredSource"] = True
    request = {"indexId": index["indexId"], "batchSize": size, "queryBson": encoded(expression)}
    if generation:
        request["generationId"] = generation
    return client.call("Search", request)


def all_results(client, response):
    generation = response["generationId"]
    results = batch_results(response)
    while int(response.get("cursorId", 0)):
        response = client.call("GetMore", {"cursorId": str(response["cursorId"]), "batchSize": 1})
        check(response["generationId"] == generation, "Cursor changed generation")
        results.extend(batch_results(response))
    return results


def ids(results):
    return {row["_id"] if "_id" in row else row["storedSource"]["_id"] for row in results}


def field_stats(client, index_id, *, page_size=100, paths=None):
    request = {"indexId": index_id, "pageSize": page_size, "paths": paths or []}
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        response = client.call("GetIndexFieldStats", request)
        if response.get("state") == "STATS_COLLECTION_STATE_READY":
            return response
        time.sleep(0.05)
    raise TimeoutError("Field statistics did not finish")


def verify_stats(client, index, expected_docs):
    response = client.call("GetIndexStats", {"indexId": index["indexId"], "refresh": True})
    check(int(response.get("documentCount", "0")) == expected_docs, "Wrong logical document count")
    size = int(response.get("indexSizeBytes", "0"))
    check(size > 0, "No snapshot storage bytes reported")
    check(
        abs(response["averageIndexBytesPerDocument"] - size / expected_docs) < 0.000001,
        "Incorrect average bytes per document",
    )
    fields = field_stats(client, index["indexId"])
    total = int(fields.get("totalAttributedDiskBytes", "0"))
    shared = int(fields.get("sharedDiskBytes", "0"))
    unknown = int(fields.get("unattributedDiskBytes", "0"))
    check(
        total + shared + unknown == int(fields["indexStats"]["indexSizeBytes"]),
        "Storage ledger does not reconcile",
    )
    check(
        sum(int(row["attributedDiskBytes"].get("value", "0")) for row in fields.get("fields", []))
        == total,
        "Field attributed totals do not match global attributed bytes",
    )
    first = field_stats(client, index["indexId"], page_size=1)
    check(bool(first.get("nextPageToken")), "Field statistics did not paginate")
    next_page = client.call(
        "GetIndexFieldStats",
        {"indexId": index["indexId"], "pageSize": 1, "pageToken": first["nextPageToken"]},
    )
    check(
        next_page["indexStats"]["snapshotId"] == first["indexStats"]["snapshotId"],
        "Pagination changed snapshot",
    )
    client.call(
        "GetIndexFieldStats",
        {"indexId": index["indexId"], "pageSize": 2, "pageToken": first["nextPageToken"]},
        error="INVALID_ARGUMENT",
    )
    selected = field_stats(client, index["indexId"], paths=["title"])
    check(
        [row["path"] for row in selected.get("fields", [])] == ["title"], "Field path filter failed"
    )
    check(
        selected.get("totalAttributedDiskBytes", "0")
        == fields.get("totalAttributedDiskBytes", "0"),
        "Filtering changed the global storage ledger",
    )
    return first["nextPageToken"]


def exercise(client):
    checks = []
    name = client.language + "-" + uuid.uuid4().hex
    check(client.call("GetStatus")["managementReady"], "Management is not ready")
    request = {
        "name": name,
        "type": "INDEX_TYPE_SEARCH",
        "definitionBson": encoded({"mappings": {"dynamic": True}, "storedSource": True}),
    }
    text = client.call("CreateIndex", request)
    check(
        client.call("CreateIndex", request)["indexId"] == text["indexId"],
        "Equivalent create changed identity",
    )
    text = ready(client, text["indexId"])
    other = client.call("CreateIndex", dict(request, name=name + "-other"))
    other = ready(client, other["indexId"])
    docs = [{"_id": str(i), "title": "coffee", "amount": Int64(2**60 + 1)} for i in range(3)]
    written = client.call(
        "Upsert", {"indexId": text["indexId"], "documentsBson": [encoded(d) for d in docs]}
    )
    check(int(written["indexedCount"]) == 3, "Wrong indexed count")
    check(
        ids(all_results(client, search(client, text, "coffee", stored=True, size=1)))
        == {"0", "1", "2"},
        "Wrong text results",
    )
    check(
        not all_results(client, search(client, other, "coffee")), "Writes leaked into another index"
    )
    checks.append("direct text writes, cursor pagination, BSON preservation, index isolation")
    verify_stats(client, text, 3)
    checks.append(
        "per-index stats, bytes per document, field ledger, filtered immutable pagination"
    )
    exported = client.call(
        "GetIndexMappings",
        {"indexId": text["indexId"], "generationId": text["generationId"]},
    )
    mapping = json.loads(exported["mappingsJson"])
    check(mapping["dynamic"] is False, "Exported mapping is not static")
    check(mapping["fields"]["amount"]["type"] == "number", "Numeric mapping missing")
    check(mapping["fields"]["title"]["type"] == "string", "String mapping missing")
    check(exported["generationId"] == text["generationId"], "Exported wrong generation")
    static = client.call(
        "CreateIndex",
        {
            "name": name + "-static",
            "type": "INDEX_TYPE_SEARCH",
            "definitionBson": encoded(loads(exported["definitionJson"])),
        },
    )
    static = ready(client, static["indexId"])
    client.call("Upsert", {"indexId": static["indexId"], "documentsBson": [encoded(docs[0])]})
    check(
        ids(all_results(client, search(client, static, "coffee"))) == {"0"},
        "Export was not reusable",
    )
    client.call("DropIndex", {"indexId": static["indexId"], "expectedRevision": "1"})
    checks.append("dynamic mapping export reused to create and query a static index")
    response = search(client, text, "coffee", size=1)
    cursor = response["cursorId"]
    client.call("KillCursor", {"cursorId": str(cursor)})
    client.call("GetMore", {"cursorId": str(cursor)}, error="NOT_FOUND")
    client.call(
        "Delete",
        {
            "indexId": text["indexId"],
            "idsBson": [encoded({"_id": "1"}), encoded({"_id": "missing"})],
        },
    )
    check(ids(all_results(client, search(client, text, "coffee"))) == {"0", "2"}, "Delete failed")
    client.call(
        "Upsert",
        {"indexId": text["indexId"], "documentsBson": [encoded({"_id": "0", "title": "tea"})]},
    )
    check(
        ids(all_results(client, search(client, text, "coffee"))) == {"2"},
        "Replacement retained old terms",
    )
    client.call(
        "Upsert",
        {"indexId": text["indexId"], "documentsBson": [encoded({"missing": "id"})]},
        error="INVALID_ARGUMENT",
    )
    listed = client.call("ListIndexes").get("indexes", [])
    check(text["indexId"] in {i["indexId"] for i in listed}, "ListIndexes lost index")
    checks.append("replacement and deletion semantics; invalid input; cursor release")
    old_generation = text["generationId"]
    client.call(
        "UpdateIndex",
        {
            "indexId": text["indexId"],
            "expectedRevision": "1",
            "definitionBson": request["definitionBson"],
        },
    )
    text = ready(client, text["indexId"], 2)
    check(text["generationId"] != old_generation, "Mapping rebuild retained generation")
    check(
        not all_results(client, search(client, text, "coffee")),
        "Mapping rebuild replayed old content",
    )
    client.call(
        "Upsert",
        {
            "indexId": text["indexId"],
            "generationId": old_generation,
            "documentsBson": [encoded(docs[0])],
        },
        error="FAILED_PRECONDITION",
    )
    client.call(
        "UpdateIndex",
        {
            "indexId": text["indexId"],
            "expectedRevision": "1",
            "definitionBson": request["definitionBson"],
        },
        error="ABORTED",
    )
    client.call(
        "Upsert",
        {
            "indexId": text["indexId"],
            "documentsBson": [encoded(docs[0])],
            "writeConcern": "DOCUMENT_WRITE_CONCERN_COMMITTED",
        },
    )
    client.call("Upsert", {"indexId": text["indexId"], "documentsBson": [encoded(docs[0])]})
    checks.append(
        "schema update starts empty; explicit reingestion; revision and generation fences"
    )
    vector = client.call(
        "CreateIndex",
        {
            "name": name + "-vectors",
            "type": "INDEX_TYPE_VECTOR_SEARCH",
            "definitionBson": encoded(
                {
                    "fields": [
                        {
                            "type": "vector",
                            "path": "embedding",
                            "numDimensions": 3,
                            "similarity": "cosine",
                        }
                    ]
                }
            ),
        },
    )
    vector = ready(client, vector["indexId"])
    client.call(
        "Upsert",
        {
            "indexId": vector["indexId"],
            "documentsBson": [encoded({"_id": "near", "embedding": [1.0, 0.0, 0.0]})],
            "writeConcern": "DOCUMENT_WRITE_CONCERN_COMMITTED",
        },
    )
    # The visible concern explicitly refreshes after a committed write.
    client.call(
        "Upsert",
        {
            "indexId": vector["indexId"],
            "documentsBson": [encoded({"_id": "near", "embedding": [1.0, 0.0, 0.0]})],
        },
    )
    vector_query = {
        "indexId": vector["indexId"],
        "queryBson": encoded(
            {"path": "embedding", "queryVector": [1.0, 0.0, 0.0], "exact": True, "limit": 1}
        ),
    }
    check(
        ids(all_results(client, client.call("VectorSearch", vector_query))) == {"near"},
        "Vector ingestion failed",
    )
    client.call("DropIndex", {"indexId": other["indexId"], "expectedRevision": "1"})
    client.call("GetIndex", {"indexId": other["indexId"]}, error="NOT_FOUND")
    vector_stats = client.call("GetIndexStats", {"indexId": vector["indexId"], "refresh": True})
    check(int(vector_stats["documentCount"]) == 1, "Wrong vector document count")
    vector_fields = field_stats(client, vector["indexId"])
    embedding = next(row for row in vector_fields["fields"] if row["path"] == "embedding")
    check(
        int(embedding["desiredVectorOffHeapBytes"]["value"]) > 0,
        "Vector working-set bytes unavailable",
    )
    check(client.called == RPC_NAMES, f"Missing RPC coverage: {RPC_NAMES - client.called}")
    checks.append("vector writes and queries; index drop; every RPC")
    return {
        "language": client.language,
        "text": text,
        "vector": vector,
        "vectorQuery": vector_query,
        "checks": checks,
        "rpcs": sorted(client.called),
    }


def verify_restart(client, state):
    text = ready(client, state["text"]["indexId"], 2)
    check(text["generationId"] == state["text"]["generationId"], "Restart changed generation")
    response = all_results(client, search(client, text, "coffee", stored=True))
    check(ids(response) == {"0"}, "Committed text disappeared after restart")
    check(response[0]["storedSource"]["amount"] == Int64(2**60 + 1), "BSON type/value changed")
    ready(client, state["vector"]["indexId"])
    check(
        ids(all_results(client, client.call("VectorSearch", state["vectorQuery"]))) == {"near"},
        "Committed vectors disappeared",
    )
    stats = client.call("GetIndexStats", {"indexId": text["indexId"], "refresh": True})
    check(
        int(stats["documentCount"]) == 1 and int(stats["indexSizeBytes"]) > 0,
        "Index stats did not recover after restart",
    )
    state["checks"].append("crash restart preserves committed Lucene content and index definitions")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--launcher")
    source.add_argument("--address")
    parser.add_argument(
        "--languages",
        nargs="+",
        choices=["python", "java", "node", "rust"],
        default=["python", "java", "node", "rust"],
    )
    parser.add_argument("--plaintext", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("/tmp/mongot-push-e2e.json"))
    args = parser.parse_args()
    directory = Path(tempfile.mkdtemp(prefix="mongot-push-e2e-"))
    print(f"Logs and isolated state: {directory}", flush=True)
    report = {
        "schemaSha256": hashlib.sha256(PROTO.read_bytes()).hexdigest(),
        "results": [],
        "passed": False,
    }
    try:
        with ExitStack() as stack:
            server = (
                stack.enter_context(Server(args.launcher, directory, args.plaintext))
                if args.launcher
                else None
            )
            environment = (
                server.environment if server else dict(os.environ, MONGOT_ADDRESS=args.address)
            )
            report["transport"] = "tls" if environment.get("MONGOT_CA") else "plaintext"
            states = []
            for language in args.languages:
                with Driver(language, environment, directory) as client:
                    state = exercise(client)
                if environment.get("MONGOT_CA"):
                    token = directory / "wrong.token"
                    token.write_text(secrets.token_hex(32))
                    with Driver(
                        language, dict(environment, MONGOT_TOKEN_FILE=str(token)), directory
                    ) as client:
                        client.call("GetStatus", error="UNAUTHENTICATED")
                    state["checks"].append("TLS bearer authentication rejects invalid tokens")
                states.append(state)
                report["results"].append(state)
                print(f"{language}: all {len(RPC_NAMES)} RPCs passed", flush=True)
            if server:
                # A hard kill proves COMMITTED data survives without any source replay path.
                server.process.kill()
                server.process.wait(timeout=15)
                server.log.close()
                reader_token = directory / "reader.token"
                stats_token = directory / "stats.token"
                if environment.get("MONGOT_CA"):
                    reader_token.write_text(secrets.token_hex(32))
                    stats_token.write_text(secrets.token_hex(32))
                    yaml = server.config.read_text()
                    yaml = yaml.replace(
                        "        indexes: ['*']",
                        "        indexes: ['*']\n"
                        + "      - name: reader\n"
                        + f"        tokenFile: {json.dumps(str(reader_token))}\n"
                        + "        roles: [query]\n"
                        + f"        indexes: ['{states[0]['text']['indexId']}']\n"
                        + "      - name: stats\n"
                        + f"        tokenFile: {json.dumps(str(stats_token))}\n"
                        + "        roles: [catalog]\n"
                        + f"        indexes: ['{states[0]['text']['indexId']}']",
                    )
                    server.config.write_text(yaml)
                server.start()
                for state in states:
                    with Driver(state["language"], environment, directory) as client:
                        verify_restart(client, state)
                    print(f"{state['language']}: crash restart passed", flush=True)
                if environment.get("MONGOT_CA"):
                    for language in args.languages:
                        with Driver(
                            language,
                            dict(environment, MONGOT_TOKEN_FILE=str(reader_token)),
                            directory,
                        ) as reader:
                            check(
                                ids(
                                    all_results(reader, search(reader, states[0]["text"], "coffee"))
                                )
                                == {"0"},
                                "Reader cannot query its granted index",
                            )
                            reader.call(
                                "Search",
                                {
                                    "indexId": states[0]["vector"]["indexId"],
                                    "queryBson": encoded(
                                        {"text": {"path": "title", "query": "coffee"}}
                                    ),
                                },
                                error="PERMISSION_DENIED",
                            )
                            reader.call(
                                "Upsert",
                                {
                                    "indexId": states[0]["text"]["indexId"],
                                    "documentsBson": [encoded({"_id": "no-write"})],
                                },
                                error="PERMISSION_DENIED",
                            )
                            reader.call("GetStatus", error="PERMISSION_DENIED")
                            reader.call(
                                "GetIndexStats",
                                {"indexId": states[0]["text"]["indexId"]},
                                error="PERMISSION_DENIED",
                            )
                            reader.call(
                                "GetIndexFieldStats",
                                {"indexId": states[0]["text"]["indexId"]},
                                error="PERMISSION_DENIED",
                            )
                            reader.call(
                                "GetIndexMappings",
                                {"indexId": states[0]["text"]["indexId"]},
                                error="PERMISSION_DENIED",
                            )
                if environment.get("MONGOT_CA"):
                    for language in args.languages:
                        with Driver(
                            language,
                            dict(environment, MONGOT_TOKEN_FILE=str(stats_token)),
                            directory,
                        ) as scoped:
                            stats = scoped.call(
                                "GetIndexStats",
                                {"indexId": states[0]["text"]["indexId"], "refresh": True},
                            )
                            check(
                                int(stats["documentCount"]) == 1,
                                "Scoped principal cannot inspect its index",
                            )
                            field_stats(scoped, states[0]["text"]["indexId"])
                            scoped.call(
                                "GetIndexStats",
                                {"indexId": states[0]["vector"]["indexId"]},
                                error="PERMISSION_DENIED",
                            )
                            scoped.call(
                                "GetIndexFieldStats",
                                {"indexId": states[0]["vector"]["indexId"]},
                                error="PERMISSION_DENIED",
                            )
                            scoped.call("GetStatus", error="PERMISSION_DENIED")
                # Catalog/journal writes atomically replace files via transient siblings. Inspect
                # the persistent inventory only after the background reconciler has stopped.
                server.stop()
                data = directory / "data"
                check(not (data / "standalone.sqlite").exists(), "Unexpected source database")
                allowed = {
                    "standalone.lock",
                    "indexes.json",
                    "configJournal.json",
                    "dynamicFeatureFlags.json",
                }
                inventory = {p.name for p in data.iterdir() if p.is_file()}
                check(inventory <= allowed, f"Unexpected content sidecar: {inventory - allowed}")
            report["passed"] = True
    except BaseException as error:
        report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {args.output}", flush=True)


if __name__ == "__main__":
    main()
