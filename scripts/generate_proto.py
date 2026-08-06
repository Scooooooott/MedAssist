from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROTO_DIR = ROOT / "contracts" / "proto"
PY_OUT = ROOT / "python-services" / "_generated"


def main() -> int:
    PY_OUT.mkdir(parents=True, exist_ok=True)
    proto_files = [str(path) for path in PROTO_DIR.glob("*.proto")]
    command = [
        sys.executable,
        "-m",
        "grpc_tools.protoc",
        f"-I{PROTO_DIR}",
        f"--python_out={PY_OUT}",
        f"--grpc_python_out={PY_OUT}",
        *proto_files,
    ]
    return subprocess.run(command, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
