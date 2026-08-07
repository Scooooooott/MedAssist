from __future__ import annotations

import sys
from pathlib import Path


def configure_generated_proto_path() -> None:
    """Make ignored repository-local protobuf output importable by services."""

    generated = Path(__file__).resolve().parents[2] / "_generated"
    if generated.is_dir() and str(generated.parent) not in sys.path:
        sys.path.insert(0, str(generated.parent))
