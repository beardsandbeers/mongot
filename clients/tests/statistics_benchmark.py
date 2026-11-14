#!/usr/bin/env python3
"""Measure bounded statistics during writes/queries against an isolated real server.

Run with the Python client installed. This is an observational benchmark, not a CI threshold.
The process I/O deltas include concurrent ingestion and queries and OS cache effects.
"""

import argparse
import json
import statistics
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from e2e import Server
from mongot_standalone import Client, api, encode_bson


def distribution(values):
    ordered = sorted(values)
    return {
        "count": len(values),
        "p50_millis": statistics.median(ordered) * 1000,
        "p95_millis": ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))] * 1000,
        "max_millis": max(ordered) * 1000,
    }


def io(pid):
    return {
        key: int(value)
        for key, value in (
            line.split(":", 1) for line in Path(f"/proc/{pid}/io").read_text().splitlines()
        )
    }


def metrics(client):
    return [
        {"name": metric.name, "labels": dict(metric.labels), "values": dict(metric.measurements)}
        for metric in client.get_status(api.GetStatusRequest()).metrics
        if metric.name.startswith("standalone.statistics.")
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher", required=True)
    parser.add_argument("--fields", type=int, default=1000)
    parser.add_argument("--documents", type=int, default=500)
    parser.add_argument(
        "--seconds", type=int, default=8, help="Duration per baseline/diagnostic phase"
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    directory = Path(tempfile.mkdtemp(prefix="mongot-statistics-benchmark-"))
    result = {"fields": args.fields, "seed_documents": args.documents, "logs": str(directory)}
    with Server(args.launcher, directory, True) as server, Client(server.address) as client:
        index = client.create_index(
            api.CreateIndexRequest(
                name="statistics-benchmark",
                type=api.INDEX_TYPE_SEARCH,
                definition_bson=encode_bson({"mappings": {"dynamic": True}}),
            )
        )
        while (
            client.get_index(api.GetIndexRequest(index_id=index.index_id)).lifecycle_state
            != "READY"
        ):
            time.sleep(0.05)
        for offset in range(0, args.documents, 10):
            client.upsert_many(
                index.index_id,
                [
                    dict(
                        {f"f{field}": f"coffee {doc} {field}" for field in range(args.fields)},
                        _id=doc,
                    )
                    for doc in range(offset, min(args.documents, offset + 10))
                ],
            )

        def phase(diagnostics):
            stopped = threading.Event()
            reads, writes, summaries, fields = [], [], [], []
            before_io, before_metrics = io(server.process.pid), metrics(client)

            def workload(query):
                with Client(server.address) as worker:
                    sequence = 0
                    while not stopped.is_set():
                        start = time.perf_counter()
                        if query:
                            response = worker.search(
                                api.QueryRequest(
                                    index_id=index.index_id,
                                    query_bson=encode_bson(
                                        {"text": {"path": "f0", "query": "coffee"}}
                                    ),
                                    batch_size=10,
                                )
                            )
                            if response.cursor_id:
                                worker.kill_cursor(
                                    api.KillCursorRequest(cursor_id=response.cursor_id)
                                )
                        else:
                            worker.upsert(
                                index.index_id, {"_id": "load", "f0": f"coffee {sequence}"}
                            )
                            sequence += 1
                        (reads if query else writes).append(time.perf_counter() - start)
                        time.sleep(0.01)

            with ThreadPoolExecutor(max_workers=2) as workers:
                jobs = [workers.submit(workload, query) for query in (False, True)]
                deadline = time.monotonic() + args.seconds
                try:
                    while time.monotonic() < deadline:
                        if diagnostics:
                            start = time.perf_counter()
                            client.get_index_stats(
                                api.GetIndexStatsRequest(index_id=index.index_id, refresh=True)
                            )
                            summaries.append(time.perf_counter() - start)
                            start = time.perf_counter()
                            request = api.GetIndexFieldStatsRequest(
                                index_id=index.index_id, refresh=True
                            )
                            page = client.get_index_field_stats(request)
                            snapshot = page.index_stats.snapshot_id
                            request.refresh = False
                            while (
                                page.state == api.STATS_COLLECTION_STATE_COLLECTING
                                or page.refresh_in_progress
                                or page.index_stats.snapshot_id == snapshot
                            ):
                                time.sleep(0.01)
                                page = client.get_index_field_stats(request)
                                if time.monotonic() > deadline + 60:
                                    raise TimeoutError("Field collection failed to complete")
                            fields.append(time.perf_counter() - start)
                        time.sleep(0.1)
                finally:
                    stopped.set()
                for job in jobs:
                    job.result()
            after_io = io(server.process.pid)
            return {
                "queries": distribution(reads),
                "writes": distribution(writes),
                "summary_rpc": distribution(summaries) if summaries else None,
                "field_collection": distribution(fields) if fields else None,
                "process_io_delta": {
                    key: after_io[key] - value for key, value in before_io.items()
                },
                "metrics_before": before_metrics,
                "metrics_after": metrics(client),
            }

        result["baseline"] = phase(False)
        result["diagnostics"] = phase(True)
        result["segment_metadata_files_at_end"] = len(list((directory / "data").rglob("*.si")))
        result["limitations"] = [
            "Single-host observational run; warm caches and merge timing affect comparisons.",
            "I/O includes writes and queries; read_bytes excludes cached reads.",
            "Allocated-byte counters measure diagnostic worker threads where JVM accounting supports them.",
            "Result cache bytes are conservative estimates, not an allocation profile or process RSS.",
        ]
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(args.output)


if __name__ == "__main__":
    main()
