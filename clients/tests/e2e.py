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
from datetime import datetime, timezone
from pathlib import Path

import bson
import grpc
from bson import Binary, Int64, ObjectId
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
        collections: ['*']
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


def await_sequence(client, index, sequence, condition="VISIBLE"):
    response = client.call(
        "AwaitSequence",
        {
            "indexId": index["indexId"],
            "generationId": index["live"]["generationId"],
            "sequence": str(sequence),
            "condition": "SEQUENCE_CONDITION_" + condition,
        },
    )
    check(
        int(response.get(condition.lower() + "Sequence", 0)) >= sequence,
        f"{condition} watermark did not reach {sequence}: {response}",
    )


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
    return {row["_id"] for row in results}


def indexed(response, count=1):
    check(response.get("complete") is True, f"Write incomplete: {response}")
    check(len(response["results"]) == count, f"Wrong mutation count: {response}")
    for item in response["results"]:
        check(
            item["state"] == "WRITE_ITEM_STATE_INDEXED" and int(item.get("statusCode", 0)) == 0,
            f"Mutation failed: {item}",
        )
        check("acceptedSequence" in item, f"Missing accepted sequence: {item}")
    return max(int(item["acceptedSequence"]) for item in response["results"])


def exercise(client):
    checks = []
    run_id = client.language + "-" + uuid.uuid4().hex
    status = client.call("GetStatus")
    check(status["managementReady"] and status["instanceId"], "Management not ready")
    collection_request = {
        "requestId": run_id + "-collection",
        "database": "client_e2e",
        "name": run_id,
    }
    collection = client.call("CreateCollection", collection_request)
    collection_id = collection["collectionId"]
    check(
        client.call("CreateCollection", collection_request)["collectionId"] == collection_id,
        "Collection retry changed identity",
    )
    check(
        client.call("GetCollection", {"collectionId": collection_id})["name"] == run_id,
        "Collection lookup mismatch",
    )
    checks.append("collection creation, lookup and idempotency")

    def write(request_id, mutation_type, document):
        return {
            "collectionId": collection_id,
            "requestId": run_id + "-" + request_id,
            "mutation": {"type": "MUTATION_TYPE_" + mutation_type, "bson": encoded(document)},
        }

    seed = {
        "_id": "a",
        "title": "original",
        "old": "obsolete",
        "category": "rebuilt",
        "embedding": [1.0, 0.0, 0.0],
    }
    client.call("Write", write("before-index", "INSERT", seed), error="FAILED_PRECONDITION")
    bootstrap = client.call(
        "CreateIndex",
        {
            "requestId": run_id + "-bootstrap",
            "collectionId": collection_id,
            "name": "ingest",
            "type": "INDEX_TYPE_SEARCH",
            "definitionBson": encoded({"mappings": {"dynamic": True}}),
        },
    )
    ready(client, bootstrap["indexId"])
    first_request = write("seed", "INSERT", seed)
    first = client.call("Write", first_request)
    sequence = indexed(first)
    check(client.call("Write", first_request) == first, "Identical retry changed result")
    check(
        client.call(
            "GetWriteResult",
            {"collectionId": collection_id, "requestId": first_request["requestId"]},
        )
        == first,
        "Retry ledger differs from write response",
    )
    conflict = write("seed", "UPSERT", seed)
    client.call("Write", conflict, error="INVALID_ARGUMENT")
    checks.append("single insert, retry ledger and request ID conflict")

    text_definition = {"mappings": {"dynamic": True}, "storedSource": True}
    create_request = {
        "requestId": run_id + "-text",
        "collectionId": collection_id,
        "name": "text",
        "type": "INDEX_TYPE_SEARCH",
        "definitionBson": encoded(text_definition),
    }
    text = client.call("CreateIndex", create_request)
    text_id = text["indexId"]
    check(
        client.call("CreateIndex", create_request)["indexId"] == text_id,
        "Index retry changed identity",
    )
    text = ready(client, text_id)
    await_sequence(client, text, sequence)
    check(
        ids(all_results(client, search(client, text, "original"))) == {"a"},
        "Historical index missed source document",
    )
    checks.append("index creation, retry and historical backfill")
    client.call(
        "DropIndex",
        {
            "requestId": run_id + "-drop-bootstrap",
            "indexId": bootstrap["indexId"],
            "expectedRevision": "1",
        },
    )

    duplicate = client.call("Write", write("duplicate", "INSERT", seed))
    check(
        duplicate["results"][0]["state"] == "WRITE_ITEM_STATE_REJECTED"
        and int(duplicate["results"][0]["statusCode"]) == 6,
        "Strict INSERT did not reject a duplicate",
    )
    check(
        "acceptedSequence" not in duplicate["results"][0], "Rejected duplicate consumed a sequence"
    )
    rich = {
        "_id": "c",
        "title": "common",
        "category": "rebuilt",
        "embedding": [0.0, 1.0, 0.0],
        "big": Int64(2**60 + 1),
        "binary": Binary(b"\x00\xff\x01"),
        "object": ObjectId(),
        "date": datetime(2025, 1, 2, tzinfo=timezone.utc),
        "nested": {"items": [1, True, None]},
    }
    documents = [
        dict(seed, _id="b", title="common"),
        rich,
        dict(seed, _id="b", title="duplicate"),
        {"title": "missingid"},
        dict(seed, _id="d", title="common"),
    ]
    batch_request = {
        "collectionId": collection_id,
        "requestId": run_id + "-batch",
        "mutations": [{"type": "MUTATION_TYPE_INSERT", "bson": encoded(doc)} for doc in documents],
    }
    batch = client.call("WriteBatch", batch_request)
    check(batch["complete"] and len(batch["results"]) == 5, "Batch response incomplete")
    check(
        [int(item.get("ordinal", 0)) for item in batch["results"]] == list(range(5)),
        "Batch reordered results",
    )
    check(
        [item["state"] for item in batch["results"]]
        == [
            "WRITE_ITEM_STATE_INDEXED",
            "WRITE_ITEM_STATE_INDEXED",
            "WRITE_ITEM_STATE_REJECTED",
            "WRITE_ITEM_STATE_REJECTED",
            "WRITE_ITEM_STATE_INDEXED",
        ],
        "Partial batch states wrong",
    )
    check(
        [int(batch["results"][i]["statusCode"]) for i in (2, 3)] == [6, 3],
        "Partial batch status codes wrong",
    )
    check(
        all("acceptedSequence" not in batch["results"][i] for i in (2, 3)),
        "Rejected batch item consumed sequence",
    )
    check(
        [int(batch["results"][i]["acceptedSequence"]) for i in (0, 1, 4)]
        == [sequence + 1, sequence + 2, sequence + 3],
        "Rejected items left a gap in collection sequencing",
    )
    sequence = max(int(item.get("acceptedSequence", 0)) for item in batch["results"])
    check(client.call("WriteBatch", batch_request) == batch, "Batch retry changed outcomes")
    await_sequence(client, text, sequence, "APPLIED")
    await_sequence(client, text, sequence, "COMMITTED")
    await_sequence(client, text, sequence)
    response = search(client, text, "common", stored=True, size=1)
    check(int(response.get("cursorId", 0)) != 0, "Search did not create a cursor")
    rows = all_results(client, response)
    check(
        {r["storedSource"]["_id"] for r in rows} == {"b", "c", "d"},
        "Cursor omitted or duplicated documents",
    )
    returned = next(r["storedSource"] for r in rows if r["storedSource"]["_id"] == "c")
    check(
        bson.encode(returned) == bson.encode(rich), "BSON stored source changed bytes/types/order"
    )
    abandoned = search(client, text, "common", size=1)
    cursor = str(abandoned["cursorId"])
    client.call("KillCursor", {"cursorId": cursor})
    client.call("GetMore", {"cursorId": cursor}, error="NOT_FOUND")
    checks.append("ordered partial batches, strict INSERT and BSON fidelity")
    checks.append("APPLIED/COMMITTED/VISIBLE watermarks, cursor paging and kill")

    replacement = {
        "_id": "a",
        "title": "changed",
        "category": "rebuilt",
        "embedding": [1.0, 0.0, 0.0],
    }
    sequence = indexed(client.call("Write", write("replace", "UPSERT", replacement)))
    await_sequence(client, text, sequence)
    check(
        not all_results(client, search(client, text, "original")),
        "UPSERT retained old indexed value",
    )
    check(
        ids(all_results(client, search(client, text, "obsolete", path="old"))) == {"b", "d"},
        "UPSERT retained omitted field",
    )
    row = all_results(client, search(client, text, "changed", stored=True))[0]["storedSource"]
    check(row == replacement, "UPSERT did not replace the full stored source")
    new = dict(replacement, _id="e", title="common")
    sequence = indexed(client.call("Write", write("new-upsert", "UPSERT", new)))
    sequence = indexed(client.call("Write", write("delete", "DELETE", {"_id": "b"})))
    sequence = indexed(client.call("Write", write("delete-missing", "DELETE", {"_id": "b"})))
    await_sequence(client, text, sequence)
    check(
        ids(all_results(client, search(client, text, "common"))) == {"c", "d", "e"},
        "Delete/upsert search results wrong",
    )
    invalid_delete = client.call(
        "Write",
        write("invalid-delete", "DELETE", {"_id": "c", "extra": True}),
    )
    check(
        invalid_delete["results"][0]["state"] == "WRITE_ITEM_STATE_REJECTED"
        and int(invalid_delete["results"][0]["statusCode"]) == 3,
        "DELETE with extra fields was not rejected",
    )
    checks.append("full replacement UPSERT, create-by-UPSERT and idempotent DELETE")

    vector = client.call(
        "CreateIndex",
        {
            "requestId": run_id + "-vector",
            "collectionId": collection_id,
            "name": "vector",
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
    await_sequence(client, vector, sequence)
    nearest = client.call(
        "VectorSearch",
        {
            "indexId": vector["indexId"],
            "batchSize": 100,
            "queryBson": encoded(
                {"path": "embedding", "queryVector": [0.0, 1.0, 0.0], "exact": True, "limit": 1}
            ),
        },
    )
    check(ids(all_results(client, nearest)) == {"c"}, "Vector nearest neighbor wrong")

    def nearest_ids(target, *, limit=1, index_id=None):
        response = client.call(
            "VectorSearch",
            {
                "indexId": index_id or vector["indexId"],
                "queryBson": encoded(
                    {"path": "embedding", "queryVector": target, "exact": True, "limit": limit}
                ),
            },
        )
        return ids(all_results(client, response))

    changed = dict(new, title="fanout", embedding=[0.0, 0.0, 1.0])
    sequence = indexed(client.call("Write", write("vector-replace", "UPSERT", changed)))
    await_sequence(client, text, sequence)
    await_sequence(client, vector, sequence)
    check(
        ids(all_results(client, search(client, text, "fanout"))) == {"e"},
        "Write did not fan out to the text index",
    )
    check(nearest_ids([0.0, 0.0, 1.0]) == {"e"}, "Vector UPSERT did not replace embedding")
    sequence = indexed(client.call("Write", write("vector-delete", "DELETE", {"_id": "e"})))
    await_sequence(client, text, sequence)
    await_sequence(client, vector, sequence)
    check(not all_results(client, search(client, text, "fanout")), "Text DELETE did not fan out")
    check(
        nearest_ids([0.0, 0.0, 1.0], limit=10) == {"a", "c", "d"},
        "Vector DELETE did not remove embedding",
    )
    sequence = indexed(client.call("Write", write("vector-restore", "UPSERT", new)))
    await_sequence(client, text, sequence)
    await_sequence(client, vector, sequence)
    checks.append("document replacement/deletion fan out to text and vector indexes")

    previous_vector_generation = vector["live"]["generationId"]
    vector_definition = {
        "fields": [
            {"type": "vector", "path": "embedding", "numDimensions": 3, "similarity": "euclidean"}
        ]
    }
    client.call(
        "UpdateIndex",
        {
            "requestId": run_id + "-vector-update",
            "indexId": vector["indexId"],
            "expectedRevision": "1",
            "definitionBson": encoded(vector_definition),
        },
    )
    vector = ready(client, vector["indexId"], 2)
    check(
        vector["live"]["generationId"] != previous_vector_generation,
        "Vector definition update did not replace generation",
    )
    await_sequence(client, vector, sequence)
    check(nearest_ids([0.0, 1.0, 0.0]) == {"c"}, "Updated vector index lost source vectors")
    dropped_vector_id = vector["indexId"]
    client.call(
        "DropIndex",
        {
            "requestId": run_id + "-vector-drop",
            "indexId": dropped_vector_id,
            "expectedRevision": "2",
        },
    )
    client.call(
        "VectorSearch",
        {
            "indexId": dropped_vector_id,
            "queryBson": encoded(
                {
                    "path": "embedding",
                    "queryVector": [0.0, 1.0, 0.0],
                    "exact": True,
                    "limit": 1,
                }
            ),
        },
        error="NOT_FOUND",
    )
    vector = client.call(
        "CreateIndex",
        {
            "requestId": run_id + "-vector-recreate",
            "collectionId": collection_id,
            "name": "vector",
            "type": "INDEX_TYPE_VECTOR_SEARCH",
            "definitionBson": encoded(vector_definition),
        },
    )
    check(vector["indexId"] != dropped_vector_id, "Recreated vector reused dropped ID")
    vector = ready(client, vector["indexId"])
    await_sequence(client, vector, sequence)
    check(nearest_ids([0.0, 1.0, 0.0]) == {"c"}, "Recreated vector index lost source vectors")
    checks.append("vector definition replacement, drop and name recreation")
    listed = []
    token = ""
    while True:
        page = client.call(
            "ListIndexes", {"collectionId": collection_id, "pageSize": 1, "pageToken": token}
        )
        listed.extend(item["indexId"] for item in page.get("indexes", []))
        token = page.get("nextPageToken", "")
        if not token:
            break
    check(
        len(listed) == 2 and set(listed) == {text_id, vector["indexId"]}, "Index pagination wrong"
    )
    checks.append("vector index backfill, nearest neighbor and catalog pagination")

    old_generation = text["live"]["generationId"]
    update_request = {
        "requestId": run_id + "-update",
        "indexId": text_id,
        "expectedRevision": "1",
        "definitionBson": encoded(
            {
                "mappings": {"dynamic": False, "fields": {"category": {"type": "string"}}},
                "storedSource": True,
            }
        ),
    }
    updated = client.call("UpdateIndex", update_request)
    check(int(updated["desiredRevision"]) == 2, "Update did not advance revision")
    check(
        int(client.call("UpdateIndex", update_request)["desiredRevision"]) == 2,
        "Update retry advanced twice",
    )
    stale = dict(update_request, requestId=run_id + "-stale")
    client.call("UpdateIndex", stale, error="ABORTED")
    text = ready(client, text_id, 2)
    check(
        text["live"]["generationId"] != old_generation, "Mapping update did not replace generation"
    )
    await_sequence(client, text, sequence)
    check(
        ids(all_results(client, search(client, text, "rebuilt", path="category")))
        == {"a", "c", "d", "e"},
        "Mapping rebuild lost documents or restored deletion",
    )
    check(
        not all_results(client, search(client, text, "common")),
        "Replacement mapping still indexed excluded field",
    )
    client.call(
        "Search",
        {
            "indexId": text_id,
            "generationId": old_generation,
            "queryBson": encoded({"text": {"path": "category", "query": "rebuilt"}}),
        },
        error="FAILED_PRECONDITION",
    )
    checks.append("mapping replacement, revision CAS/retry and generation fencing")

    drop_cursor = search(client, text, "rebuilt", path="category", size=1)
    drop_request = {"requestId": run_id + "-drop", "indexId": text_id, "expectedRevision": "2"}
    client.call(
        "DropIndex",
        dict(drop_request, requestId=run_id + "-stale-drop", expectedRevision="1"),
        error="ABORTED",
    )
    dropped = client.call("DropIndex", drop_request)
    check(dropped["lifecycleState"] == "DROPPED", "Drop did not tombstone index")
    check(
        client.call("DropIndex", drop_request)["lifecycleState"] == "DROPPED",
        "Drop retry changed state",
    )
    check(
        client.call("GetIndex", {"indexId": text_id})["lifecycleState"] == "DROPPED",
        "Dropped index lookup wrong",
    )
    client.call("GetMore", {"cursorId": str(drop_cursor["cursorId"])}, error="NOT_FOUND")
    client.call(
        "Search",
        {"indexId": text_id, "queryBson": encoded({"text": {"path": "title", "query": "common"}})},
        error="NOT_FOUND",
    )
    check(
        [
            i["indexId"]
            for i in client.call("ListIndexes", {"collectionId": collection_id}).get("indexes", [])
        ]
        == [vector["indexId"]],
        "Dropped index remained listed",
    )
    recreated = client.call("CreateIndex", dict(create_request, requestId=run_id + "-recreate"))
    check(recreated["indexId"] != text_id, "Recreated name reused dropped ID")
    recreated = ready(client, recreated["indexId"])
    await_sequence(client, recreated, sequence)
    check(
        ids(all_results(client, search(client, recreated, "common"))) == {"c", "d", "e"},
        "Drop/recreate lost source or restored deleted document",
    )
    check(
        int(client.call("GetCollection", {"collectionId": collection_id})["acceptedSequence"])
        == sequence,
        "Collection watermark wrong",
    )
    exercise_document_commands(client, recreated["indexId"])
    check(client.called == RPC_NAMES, f"Untested RPCs: {RPC_NAMES - client.called}")
    checks.append("drop, stale revision rejection, cursor invalidation and name recreation")
    return {
        "language": client.language,
        "collectionId": collection_id,
        "text": recreated,
        "vector": vector,
        "sequence": sequence,
        "retryRequest": batch_request,
        "retryResponse": batch,
        "instanceId": status["instanceId"],
        "checks": checks,
        "rpcs": sorted(client.called),
    }


def exercise_document_commands(client, other_index_id):
    run_id = "commands-" + client.language + "-" + uuid.uuid4().hex
    collection = client.call(
        "CreateCollection", {"requestId": run_id, "database": "commands", "name": run_id}
    )["collectionId"]
    index = client.call(
        "CreateIndex",
        {
            "requestId": run_id + "-index",
            "collectionId": collection,
            "name": "text",
            "type": "INDEX_TYPE_SEARCH",
            "definitionBson": encoded({"mappings": {"dynamic": True}}),
        },
    )
    index = ready(client, index["indexId"])
    oid = ObjectId()
    documents = [
        {"_id": "a", "title": "remove"},
        {"_id": "b", "title": "remove"},
        {"_id": "keep", "title": "keep"},
        {"_id": oid, "title": "remove"},
        {"_id": 7, "title": "remove"},
        {"_id": Int64(7), "title": "keep"},
    ]
    upsert = {
        "collectionId": collection,
        "requestId": run_id + "-upsert",
        "documentsBson": [encoded(document) for document in documents],
    }
    inserted = client.call("Upsert", upsert)
    check(int(inserted["upsertedCount"]) == 6, "Upsert did not report all documents")
    check(client.call("Upsert", upsert) == inserted, "Upsert retry changed result")
    check(
        len(all_results(client, search(client, index, "remove"))) == 4,
        "Upsert default concern did not provide reader visibility",
    )
    delete = {
        "collectionId": collection,
        "requestId": run_id + "-delete",
        "ids": [
            {"stringValue": "a"},
            {"objectId": str(oid)},
            {"int32Value": 7},
            {"stringValue": "absent"},
        ],
    }
    deleted = client.call("Delete", delete)
    check(int(deleted["deletedCount"]) == 3, "Delete count included an absent ID")
    check(
        client.call("Delete", delete) == deleted, "Delete retry did not preserve its original count"
    )
    check(
        ids(all_results(client, search(client, index, "remove"))) == {"b"},
        "Typed deletion removed the wrong documents",
    )
    keep = all_results(client, search(client, index, "keep"))
    check(
        len(keep) == 2 and any(isinstance(row["_id"], Int64) for row in keep),
        "int32 deletion also deleted the int64 ID",
    )
    client.call(
        "Upsert",
        {
            "collectionId": collection,
            "requestId": run_id + "-more",
            "documentsBson": [
                encoded({"_id": "b", "title": "remove", "changed": True}),
                encoded({"_id": "d", "title": "remove"}),
                encoded({"_id": "e", "title": "remove"}),
            ],
        },
    )
    query = {
        "collectionId": collection,
        "requestId": run_id + "-query",
        "indexId": index["indexId"],
        "queryBson": encoded({"text": {"path": "title", "query": "remove"}}),
        "batchSize": 1,
    }
    result = client.call("DeleteByQuery", query)
    check(
        result["complete"]
        and result["selectionComplete"]
        and int(result["matchedCount"]) == 3
        and int(result["deletedCount"]) == 3
        and int(result.get("conflictCount", 0)) == 0,
        "DeleteByQuery did not complete its full cursor selection",
    )
    check(
        not all_results(client, search(client, index, "remove")),
        "DeleteByQuery default concern was not visible",
    )
    client.call(
        "Upsert",
        {
            "collectionId": collection,
            "requestId": run_id + "-late",
            "documentsBson": [encoded({"_id": "late", "title": "remove"})],
        },
    )
    check(
        client.call("DeleteByQuery", query) == result,
        "DeleteByQuery retry changed its frozen selection",
    )
    check(
        client.call(
            "GetDeleteByQueryResult", {"collectionId": collection, "requestId": query["requestId"]}
        )
        == result,
        "DeleteByQuery result lookup differs from its durable outcome",
    )
    check(
        ids(all_results(client, search(client, index, "remove"))) == {"late"},
        "Query retry deleted a newly matching document",
    )
    client.call(
        "DeleteByQuery",
        dict(query, queryBson=encoded({"text": {"path": "title", "query": "keep"}})),
        error="INVALID_ARGUMENT",
    )
    client.call(
        "GetWriteResult",
        {"collectionId": collection, "requestId": query["requestId"]},
        error="FAILED_PRECONDITION",
    )
    client.call(
        "Delete",
        {"collectionId": collection, "requestId": run_id + "-bad-id", "ids": [{}]},
        error="INVALID_ARGUMENT",
    )
    client.call(
        "DeleteByQuery",
        dict(query, requestId=run_id + "-wrong-collection", indexId=other_index_id),
        error="INVALID_ARGUMENT",
    )
    partial = {
        "collectionId": collection,
        "requestId": run_id + "-partial",
        "documentsBson": [
            encoded({"_id": "valid", "title": "keep"}),
            encoded({"title": "missingid"}),
        ],
    }
    client.call("Upsert", partial, error="INVALID_ARGUMENT")
    outcomes = client.call(
        "GetWriteResult", {"collectionId": collection, "requestId": partial["requestId"]}
    )
    check(
        outcomes["complete"]
        and [item["state"] for item in outcomes["results"]]
        == ["WRITE_ITEM_STATE_INDEXED", "WRITE_ITEM_STATE_REJECTED"],
        "Partial command outcomes were lost",
    )


def verify_restart(client, state):
    check(
        client.call("GetStatus")["instanceId"] == state["instanceId"], "Restart changed instance ID"
    )
    text = ready(client, state["text"]["indexId"])
    await_sequence(client, text, state["sequence"])
    check(
        ids(all_results(client, search(client, text, "common"))) == {"c", "d", "e"},
        "Restart lost source/index data",
    )
    check(
        client.call("WriteBatch", state["retryRequest"]) == state["retryResponse"],
        "Restart lost retry ledger",
    )
    vector = ready(client, state["vector"]["indexId"])
    await_sequence(client, vector, state["sequence"])
    result = client.call(
        "VectorSearch",
        {
            "indexId": vector["indexId"],
            "queryBson": encoded(
                {"path": "embedding", "queryVector": [0.0, 1.0, 0.0], "exact": True, "limit": 1}
            ),
        },
    )
    check(ids(all_results(client, result)) == {"c"}, "Restart changed vector result")
    state["checks"].append("process restart preserves identity, source, indexes and retry outcomes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument(
        "--launcher",
        help="Path to packaged mongot-standalone; runs isolated state and verifies restart",
    )
    source.add_argument(
        "--address", help="Existing disposable server; uses MONGOT_CA/MONGOT_TOKEN_FILE; no restart"
    )
    parser.add_argument(
        "--languages",
        nargs="+",
        choices=["python", "java", "node", "rust"],
        default=["python", "java", "node", "rust"],
    )
    parser.add_argument(
        "--plaintext",
        action="store_true",
        help="Use plaintext for the isolated server (default TLS/auth)",
    )
    parser.add_argument("--output", type=Path, default=Path("/tmp/mongot-client-e2e-results.json"))
    args = parser.parse_args()
    if args.address and args.plaintext:
        parser.error("--plaintext applies only to --launcher")
    directory = Path(tempfile.mkdtemp(prefix="mongot-client-e2e-"))
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
                if server and environment.get("MONGOT_CA"):
                    wrong_token = directory / "wrong.token"
                    wrong_token.write_text(secrets.token_hex(32))
                    wrong_token.chmod(0o600)
                    with Driver(
                        language, dict(environment, MONGOT_TOKEN_FILE=str(wrong_token)), directory
                    ) as client:
                        client.call("GetStatus", error="UNAUTHENTICATED")
                    state["checks"].append("TLS bearer authentication rejects an invalid token")
                with Driver(language, dict(environment, MONGOT_TIMEOUT="1"), directory) as client:
                    client.call(
                        "AwaitSequence",
                        {
                            "indexId": state["text"]["indexId"],
                            "generationId": state["text"]["live"]["generationId"],
                            "sequence": str(state["sequence"] + 1000),
                            "condition": "SEQUENCE_CONDITION_VISIBLE",
                        },
                        error="DEADLINE_EXCEEDED",
                    )
                state["checks"].append("per-call deadline bounds a blocked sequence wait")
                states.append(state)
                report["results"].append(
                    {k: state[k] for k in ("language", "collectionId", "checks", "rpcs")}
                )
                print(
                    f"{language}: all {len(RPC_NAMES)} RPCs and {len(state['checks'])} behavior checks passed",
                    flush=True,
                )
            if server:
                server.stop()
                server.start()
                for state in states:
                    with Driver(state["language"], environment, directory) as client:
                        verify_restart(client, state)
                    print(f"{state['language']}: restart verification passed", flush=True)
            report["passed"] = True
    except BaseException as error:
        report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {args.output}", flush=True)


if __name__ == "__main__":
    main()
