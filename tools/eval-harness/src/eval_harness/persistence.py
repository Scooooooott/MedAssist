from __future__ import annotations

import json
import math
from typing import Any


class PersistenceError(RuntimeError):
    pass


EVALUATION_RUN_COLUMNS = (
    "eval_set_version",
    "split",
    "code_commit",
    "model_name",
    "model_version",
    "judge_model",
    "random_seed",
    "metrics",
    "result_uri",
)
FORBIDDEN_METRIC_KEYS = {"question", "answer", "prompt", "results", "raw_records"}


def persist_report(payload: dict[str, Any], dsn: str) -> str:
    """Insert aggregate evaluation metadata into the Flyway-owned table only."""
    if not dsn.strip():
        raise PersistenceError("PostgreSQL persistence was requested with an empty DSN")
    try:
        import psycopg  # type: ignore[import-not-found]
    except ImportError as exc:
        raise PersistenceError(
            "PostgreSQL DSN was supplied, but optional dependency 'psycopg' is not installed"
        ) from exc
    metadata = payload.get("metadata")
    metrics = payload.get("metrics")
    if not isinstance(metadata, dict) or not isinstance(metrics, dict):
        raise PersistenceError("report is missing metadata or metrics for PostgreSQL persistence")
    values = _run_values(payload, metadata, metrics)
    try:
        with psycopg.connect(dsn) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO evaluation_run (
                        eval_set_version, split, code_commit, model_name, model_version,
                        judge_model, random_seed, metrics, result_uri
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s)
                    RETURNING id
                    """,
                    values,
                )
                row = cursor.fetchone()
                if not row or not row[0]:
                    raise PersistenceError("evaluation_run insert did not return its UUID")
                return str(row[0])
    except PersistenceError:
        raise
    except Exception as exc:
        if getattr(exc, "sqlstate", None) == "42P01":
            raise PersistenceError(
                "Flyway-managed table evaluation_run is missing; apply Flyway migrations"
            ) from exc
        raise PersistenceError(
            "PostgreSQL persistence failed; the report was not silently discarded"
        ) from exc


def _run_values(
    payload: dict[str, Any], metadata: dict[str, Any], metrics: dict[str, Any]
) -> tuple[object, ...]:
    required = (
        "eval_set_version",
        "code_commit",
        "model_name",
        "model_version",
        "judge_model",
        "random_seed",
    )
    missing = [name for name in required if not _present(metadata.get(name))]
    if not _present(metadata.get("split")):
        missing.append("split")
    if missing:
        raise PersistenceError("evaluation metadata is missing: " + ", ".join(missing))
    seed = metadata["random_seed"]
    if not isinstance(seed, int) or isinstance(seed, bool):
        raise PersistenceError("random_seed must be an integer")
    aggregate_metrics = _aggregate_metrics(metrics)
    result_uri = payload.get("result_uri", metadata.get("result_uri"))
    if result_uri is not None and (not isinstance(result_uri, str) or not result_uri.strip()):
        raise PersistenceError("result_uri must be a non-empty string when supplied")
    return (
        metadata["eval_set_version"],
        metadata["split"],
        metadata["code_commit"],
        metadata["model_name"],
        metadata["model_version"],
        metadata["judge_model"],
        seed,
        json.dumps(aggregate_metrics, sort_keys=True),
        result_uri,
    )


def _aggregate_metrics(value: dict[str, Any]) -> dict[str, object]:
    """Keep only numeric aggregate leaves and reject raw record-shaped values."""
    result: dict[str, object] = {}
    for key, item in value.items():
        if not isinstance(key, str) or not key.strip():
            raise PersistenceError("metric names must be non-empty strings")
        if key.lower() in FORBIDDEN_METRIC_KEYS:
            raise PersistenceError("metrics may not contain raw evaluation fields")
        if key in {"status", "source"}:
            continue
        if isinstance(item, dict):
            nested = _aggregate_metrics(item)
            if nested:
                result[key] = nested
            continue
        if isinstance(item, list) or isinstance(item, str) or item is None:
            raise PersistenceError("metrics may contain aggregate numeric values only")
        if isinstance(item, bool) or not isinstance(item, int | float) or not math.isfinite(item):
            raise PersistenceError("metrics may contain aggregate numeric values only")
        result[key] = item
    if not result:
        raise PersistenceError("metrics must contain at least one aggregate value")
    return result


def _present(value: object) -> bool:
    return isinstance(value, int) or bool(str(value).strip())
