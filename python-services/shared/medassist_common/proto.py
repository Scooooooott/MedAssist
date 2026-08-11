from __future__ import annotations

import os
import sys
from pathlib import Path


def configure_generated_proto_path() -> None:
    """Make ignored repository-local protobuf output importable by services."""

    configured = os.getenv("MEDASSIST_GENERATED_PROTO_DIR")
    candidates = [Path(configured)] if configured else []
    for anchor in (Path.cwd(), Path(__file__).resolve()):
        for parent in (anchor, *anchor.parents):
            candidates.extend((parent / "_generated", parent / "python-services" / "_generated"))

    for generated in candidates:
        if (generated / "medassist" / "contracts" / "v1").is_dir():
            value = str(generated.resolve())
            if value not in sys.path:
                sys.path.insert(0, value)
            return
