from __future__ import annotations

import json
import os
import tempfile
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


class HoldoutError(ValueError):
    pass


def load_holdout_metadata(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise HoldoutError(f"unable to read holdout metadata: {path}") from exc
    if not isinstance(document, dict):
        raise HoldoutError("holdout metadata must be a JSON object")
    return document


def assert_holdout_available(
    metadata: dict[str, Any],
    version: str,
    eval_set_version: str,
) -> dict[str, object]:
    _assert_eval_set_version(metadata, eval_set_version)
    subset = _find_subset(metadata, version)
    status = subset.get("status")
    if status == "consumed":
        raise HoldoutError(f"holdout subset {version} is already consumed and cannot be evaluated")
    if status not in {"available", "reserved"}:
        raise HoldoutError(f"holdout subset {version} has invalid status {status!r}")
    return {
        "version": version,
        "status": str(status),
        "consumption_count": _usage_count(subset),
    }


def mark_holdout_consumed(
    path: Path,
    metadata: dict[str, Any],
    version: str,
    code_commit: str,
    model_version: str,
) -> dict[str, object]:
    subset = _find_subset(metadata, version)
    if subset.get("status") == "consumed":
        raise HoldoutError(f"holdout subset {version} is already consumed and cannot be evaluated")
    count = _usage_count(subset) + 1
    subset["status"] = "consumed"
    subset["consumption_count"] = count
    subset["consumed_at"] = datetime.now(UTC).isoformat()
    subset["consumed_by"] = {"code_commit": code_commit, "model_version": model_version}
    _atomic_write_json(path, metadata)
    return {"version": version, "status": "consumed", "consumption_count": count}


def _find_subset(metadata: dict[str, Any], version: str) -> dict[str, Any]:
    subsets = metadata.get("holdout_subsets", metadata.get("holdouts"))
    if not isinstance(subsets, list):
        raise HoldoutError("holdout metadata must contain a 'holdout_subsets' list")
    for subset in subsets:
        if isinstance(subset, dict) and subset.get("version") == version:
            return subset
    raise HoldoutError(f"holdout subset {version} is not declared in metadata")


def _assert_eval_set_version(metadata: dict[str, Any], expected: str) -> None:
    actual = metadata.get("eval_set_version")
    if actual != expected:
        raise HoldoutError(
            f"holdout metadata eval_set_version {actual!r} does not match report {expected!r}"
        )


def _usage_count(subset: dict[str, Any]) -> int:
    value = subset.get("consumption_count", subset.get("usage_count", 0))
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise HoldoutError("holdout consumption_count must be a non-negative integer")
    return value


def _atomic_write_json(path: Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(document, handle, indent=2, ensure_ascii=True, sort_keys=True)
            handle.write("\n")
        os.replace(temporary_name, path)
    except OSError:
        try:
            os.unlink(temporary_name)
        except OSError:
            pass
        raise
