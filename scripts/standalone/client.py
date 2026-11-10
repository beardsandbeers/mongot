#!/usr/bin/env python3
"""Generated-stub example and bounded load client for a disposable standalone collection."""

import argparse
import concurrent.futures
import json
import math
import time
import uuid
from pathlib import Path

import bson
import grpc
import standalone_pb2 as api
import standalone_pb2_grpc as rpc


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def run(args):
    credentials = grpc.ssl_channel_credentials(Path(args.ca).read_bytes()) if args.ca else None
    channel = (
        grpc.secure_channel(args.address, credentials)
        if credentials
        else grpc.insecure_channel(args.address)
    )
    metadata = (
        [("authorization", "Bearer " + Path(args.token_file).read_text().strip())]
        if args.token_file
        else []
    )
    catalog = rpc.CatalogServiceStub(channel)
    documents = rpc.DocumentServiceStub(channel)
    queries = rpc.QueryServiceStub(channel)
    operations = rpc.OperationsServiceStub(channel)
    options = {"timeout": args.deadline, "metadata": metadata}
    run_id = uuid.uuid4().hex

    def ready(index_id, revision=1):
        until = time.monotonic() + args.deadline
        while time.monotonic() < until:
            index = catalog.GetIndex(api.GetIndexRequest(index_id=index_id), **options)
            if index.lifecycle_state == "FAILED" or index.failure_message:
                raise RuntimeError(index.failure_message or "Index initialization failed")
            if index.lifecycle_state == "READY" and index.active_revision == revision:
                return index
            time.sleep(0.05)
        raise TimeoutError("Index did not become ready")

    try:
        collection = catalog.CreateCollection(
            api.CreateCollectionRequest(
                request_id=run_id + "-collection", database="benchmark", name=run_id
            ),
            **options,
        )
        index = catalog.CreateIndex(
            api.CreateIndexRequest(
                request_id=run_id + "-index",
                collection_id=collection.collection_id,
                name="text",
                type=api.INDEX_TYPE_SEARCH,
                definition_bson=bson.encode({"mappings": {"dynamic": True}}),
            ),
            **options,
        )
        index = ready(index.index_id)

        def write_batch(start):
            request = api.WriteBatchRequest(
                collection_id=collection.collection_id,
                request_id=f"{run_id}-{start}",
                mutations=[
                    api.Mutation(
                        type=api.MUTATION_TYPE_INSERT,
                        bson=bson.encode(
                            {
                                "_id": str(i),
                                "title": "capacityterm",
                                "payload": "x" * args.payload_bytes,
                            }
                        ),
                    )
                    for i in range(start, min(start + args.batch_size, args.documents))
                ],
            )
            began = time.perf_counter()
            response = documents.WriteBatch(request, **options)
            elapsed = (time.perf_counter() - began) * 1000
            if not response.complete or any(
                result.state != api.WRITE_ITEM_STATE_INDEXED for result in response.results
            ):
                raise RuntimeError(f"A batch did not finish indexing: {response}")
            return elapsed, max(result.accepted_sequence for result in response.results)

        began = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            # Keep at most concurrency batches pending; do not materialize every document/request.
            starts = iter(range(0, args.documents, args.batch_size))
            pending = {
                pool.submit(write_batch, start) for start in list_starts(starts, args.concurrency)
            }
            results = []
            while pending:
                completed, pending = concurrent.futures.wait(
                    pending, return_when=concurrent.futures.FIRST_COMPLETED
                )
                for future in completed:
                    results.append(future.result())
                    start = next(starts, None)
                    if start is not None:
                        pending.add(pool.submit(write_batch, start))
        elapsed = time.perf_counter() - began
        sequence = max(result[1] for result in results)
        documents.AwaitSequence(
            api.AwaitSequenceRequest(
                index_id=index.index_id,
                generation_id=index.live.generation_id,
                sequence=sequence,
                condition=api.SEQUENCE_CONDITION_VISIBLE,
            ),
            **options,
        )
        response = queries.Search(
            api.QueryRequest(
                index_id=index.index_id,
                batch_size=1000,
                query_bson=bson.encode({"text": {"path": "title", "query": "capacityterm"}}),
            ),
            **options,
        )
        count = len(bson.decode(response.batch_bson)["results"])
        while response.cursor_id:
            response = queries.GetMore(
                api.GetMoreRequest(cursor_id=response.cursor_id, batch_size=1000),
                **options,
            )
            count += len(bson.decode(response.batch_bson)["results"])
        if count != args.documents:
            raise RuntimeError(f"Expected {args.documents} searchable documents, found {count}")
        status = operations.GetStatus(api.GetStatusRequest(), **options)
        latencies = [result[0] for result in results]
        print(
            json.dumps(
                {
                    "collection_id": collection.collection_id,
                    "index_id": index.index_id,
                    "documents": args.documents,
                    "batch_size": args.batch_size,
                    "concurrency": args.concurrency,
                    "payload_bytes": args.payload_bytes,
                    "elapsed_seconds": round(elapsed, 3),
                    "indexed_documents_per_second": round(args.documents / elapsed, 1),
                    "batch_latency_ms": {
                        str(p): round(percentile(latencies, p / 100), 2) for p in (50, 95, 99)
                    },
                    "verified_search_results": count,
                    "accepted_sequence": sequence,
                    "logical_log_bytes": status.logical_log_bytes,
                    "source_bytes": status.source_bytes,
                    "sqlite_wal_bytes": status.sqlite_wal_bytes,
                    "free_disk_bytes": status.free_disk_bytes,
                },
                indent=2,
            )
        )
    finally:
        channel.close()


def list_starts(starts, limit):
    for _ in range(limit):
        start = next(starts, None)
        if start is None:
            return
        yield start


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", default="127.0.0.1:27029")
    parser.add_argument("--ca", help="PEM certificate authority for TLS")
    parser.add_argument("--token-file", help="Bearer token file")
    parser.add_argument("--documents", type=int, default=1000)
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--payload-bytes", type=int, default=256)
    parser.add_argument("--deadline", type=float, default=60)
    args = parser.parse_args()
    if (
        args.documents < 1
        or not 1 <= args.batch_size <= 1000
        or not 1 <= args.concurrency <= 64
        or not 0 <= args.payload_bytes <= 1024 * 1024
        or args.deadline <= 0
    ):
        parser.error("Invalid document, batch, concurrency, payload, or deadline limit")
    run(args)


if __name__ == "__main__":
    main()
