"""Regenerate bindings from the repository's authoritative API."""

import subprocess
import sys
from pathlib import Path

here = Path(__file__).resolve().parent
source = here.parent.parent / "src/proto/standalone/v1"
output = here / "mongot_standalone"
subprocess.run(
    [
        sys.executable,
        "-m",
        "grpc_tools.protoc",
        f"-I{source}",
        f"--python_out={output}",
        f"--pyi_out={output}",
        f"--grpc_python_out={output}",
        str(source / "standalone.proto"),
    ],
    check=True,
)
stub = output / "standalone_pb2_grpc.py"
stub.write_text(
    stub.read_text().replace("import standalone_pb2 as", "from . import standalone_pb2 as")
)
