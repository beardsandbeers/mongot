#!/usr/bin/env python3
"""Copy/check the authoritative API needed by the independently installable Rust crate."""

import argparse
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
source = root / "src/proto/standalone/v1/standalone.proto"
target = root / "clients/rust/proto/standalone.proto"
if args.check:
    if not target.exists() or target.read_bytes() != source.read_bytes():
        parser.exit(1, "Packaged API is stale: run python3 clients/sync_proto.py\n")
else:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(source.read_bytes())
