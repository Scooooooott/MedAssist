from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROTO_DIR = ROOT / "contracts" / "proto"
PY_OUT = ROOT / "python-services" / "_generated"


def main() -> int:
    PY_OUT.mkdir(parents=True, exist_ok=True)
    proto_files = [str(path) for path in sorted(PROTO_DIR.rglob("*.proto"))]
    command = [
        sys.executable,
        "-m",
        "grpc_tools.protoc",
        f"-I{PROTO_DIR}",
        f"--python_out={PY_OUT}",
        f"--grpc_python_out={PY_OUT}",
        *proto_files,
    ]
    result = subprocess.run(command, check=False)
    if result.returncode != 0:
        return result.returncode

    # grpcio-tools emits flat modules with absolute imports. Make the ignored
    # output a regular package so every service can import it consistently.
    for directory in [PY_OUT, *[path for path in PY_OUT.rglob("*") if path.is_dir()]]:
        (directory / "__init__.py").write_text(
            '"""Generated protobuf modules; run scripts/generate_proto.py to refresh."""\n',
            encoding="utf-8",
        )
    import_pattern = re.compile(r"^import ([a-z][a-z0-9_]*_pb2) as ([a-z][a-z0-9_]*__pb2)$")
    for generated_file in PY_OUT.rglob("*_pb2*.py"):
        lines = generated_file.read_text(encoding="utf-8").splitlines(keepends=True)
        patched: list[str] = []
        for line in lines:
            match = import_pattern.match(line.rstrip("\r\n"))
            if match:
                newline = "\r\n" if line.endswith("\r\n") else "\n"
                line = f"from . import {match.group(1)} as {match.group(2)}{newline}"
            patched.append(line)
        generated_file.write_text("".join(patched), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
