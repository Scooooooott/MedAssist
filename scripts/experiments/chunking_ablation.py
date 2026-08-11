"""Plan and safely merge the M2.5 chunking ablation.

This module deliberately has no service, database, model, or LLM client.  The
plan is a source-text-free contract for an external runner; the merge path only
accepts aggregate numeric metrics and artifact URIs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

STRATEGIES = ("fixed", "structure", "semantic")
TARGET_TOKENS = (256, 512, 1024)
OVERLAP_TOKENS = (0, 50, 128)

# These are the controls needed to make a cross-strategy comparison attributable.
# The experiment must fail closed when any of them is absent.
REQUIRED_CONTROLS = (
    "source_corpus_version",
    "eval_set_version",
    "embedding_model",
    "embedding_model_version",
    "embedding_dimension",
    "retrieval_mode",
    "context_mode",
    "rerank_enabled",
    "rrf_k",
    "candidate_top_n",
    "final_top_k",
    "filters",
    "tokenizer",
    "source_range_index_version",
    "hardware",
    "schema_version",
    "code_commit",
    "seed",
)

_FORBIDDEN_KEY_PARTS = (
    "question",
    "answer",
    "prompt",
    "source_text",
    "raw_text",
    "chunk_text",
    "chunk_id",
    "document_text",
    "completion",
    "phi",
)


class ExperimentError(ValueError):
    """Raised when an experiment plan or result violates its contract."""


class MissingControlError(ExperimentError):
    """Raised when a required control variable is missing or empty."""


class ResultValidationError(ExperimentError):
    """Raised when a result contains unsafe or incomplete data."""


JSONValue = Any


@dataclass(frozen=True)
class RunConfig:
    run_id: str
    strategy: str
    target_tokens: int
    overlap_tokens: int
    controls: dict[str, JSONValue]
    config_sha256: str
    command: str | None = None

    def as_dict(self) -> dict[str, JSONValue]:
        payload: dict[str, JSONValue] = {
            "run_id": self.run_id,
            "strategy": self.strategy,
            "targetTokens": self.target_tokens,
            "overlapTokens": self.overlap_tokens,
            "controls": self.controls,
            "config_sha256": self.config_sha256,
        }
        if self.command is not None:
            payload["command"] = self.command
        return payload


@dataclass(frozen=True)
class ExperimentPlan:
    controls: dict[str, JSONValue]
    runs: tuple[RunConfig, ...]

    def as_dict(self) -> dict[str, JSONValue]:
        return {
            "experiment": "m2.5-chunking-ablation",
            "status": "PLANNED",
            "run_count": len(self.runs),
            "controls": self.controls,
            "runs": [run.as_dict() for run in self.runs],
        }


def build_plan(
    controls: Mapping[str, JSONValue],
    *,
    command_template: str | None = None,
) -> ExperimentPlan:
    """Build the deterministic 27-run matrix without executing anything."""

    normalized_controls = validate_controls(controls)
    runs: list[RunConfig] = []
    for strategy in STRATEGIES:
        for target_tokens in TARGET_TOKENS:
            for overlap_tokens in OVERLAP_TOKENS:
                if overlap_tokens >= target_tokens:
                    raise ExperimentError(
                        f"overlapTokens must be less than targetTokens: {overlap_tokens} >= "
                        f"{target_tokens}"
                    )
                base = {
                    "strategy": strategy,
                    "targetTokens": target_tokens,
                    "overlapTokens": overlap_tokens,
                    "controls": normalized_controls,
                }
                digest = sha256_json(base)
                run_id = (
                    f"m25-{strategy}-{target_tokens}-{overlap_tokens}-{digest[:12]}"
                )
                command = render_command(command_template, run_id=run_id, config=base)
                runs.append(
                    RunConfig(
                        run_id=run_id,
                        strategy=strategy,
                        target_tokens=target_tokens,
                        overlap_tokens=overlap_tokens,
                        controls=normalized_controls,
                        config_sha256=digest,
                        command=command,
                    )
                )
    plan = ExperimentPlan(controls=normalized_controls, runs=tuple(runs))
    _assert_unique_run_ids(plan.runs)
    return plan


def validate_controls(controls: Mapping[str, JSONValue]) -> dict[str, JSONValue]:
    """Validate and canonicalize the fixed controls used by every run."""

    if not isinstance(controls, Mapping):
        raise MissingControlError("controls must be a JSON object")
    missing = [
        name
        for name in REQUIRED_CONTROLS
        if name not in controls or controls[name] is None or controls[name] == ""
    ]
    if missing:
        raise MissingControlError("missing required controls: " + ", ".join(missing))
    normalized = _json_object(controls, label="controls")
    if not isinstance(normalized["seed"], int) or isinstance(normalized["seed"], bool):
        raise MissingControlError("seed must be an integer")
    if not isinstance(normalized["embedding_dimension"], int):
        raise MissingControlError("embedding_dimension must be an integer")
    if not isinstance(normalized["rerank_enabled"], bool):
        raise MissingControlError("rerank_enabled must be a boolean")
    for name in ("rrf_k", "candidate_top_n", "final_top_k"):
        if not isinstance(normalized[name], (int, float)) or isinstance(
            normalized[name], bool
        ):
            raise MissingControlError(f"{name} must be numeric")
    _reject_sensitive_keys(normalized, path="controls")
    return normalized


def sha256_json(value: Mapping[str, JSONValue]) -> str:
    """Hash a JSON object using a stable, whitespace-free representation."""

    encoded = json.dumps(
        value, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    )
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def render_command(
    template: str | None,
    *,
    run_id: str,
    config: Mapping[str, JSONValue],
) -> str | None:
    """Render a command for inspection; never execute it."""

    if template is None:
        return None
    params = {
        "run_id": run_id,
        "strategy": config["strategy"],
        "targetTokens": config["targetTokens"],
        "overlapTokens": config["overlapTokens"],
        "config_sha256": sha256_json(config),
    }
    try:
        return template.format(**params)
    except (KeyError, IndexError, ValueError) as exc:
        raise ExperimentError(f"invalid command template: {exc}") from exc


def detect_control_drift(
    expected: Mapping[str, JSONValue], actual: Mapping[str, JSONValue]
) -> dict[str, tuple[JSONValue, JSONValue]]:
    """Return changed or missing controls as ``field: (expected, actual)``."""

    expected_controls = validate_controls(expected)
    actual_controls = validate_controls(actual)
    drift: dict[str, tuple[JSONValue, JSONValue]] = {}
    for name in sorted(set(expected_controls) | set(actual_controls)):
        expected_value = expected_controls.get(name)
        actual_value = actual_controls.get(name)
        if expected_value != actual_value:
            drift[name] = (expected_value, actual_value)
    return drift


def merge_results(
    plan: ExperimentPlan,
    results: Sequence[Mapping[str, JSONValue]],
) -> dict[str, JSONValue]:
    """Validate and merge safe per-run aggregates; never calculate metrics."""

    _assert_unique_run_ids(plan.runs)
    expected = {run.run_id: run for run in plan.runs}
    seen: set[str] = set()
    merged: list[dict[str, JSONValue]] = []
    for index, raw_result in enumerate(results, start=1):
        result = _json_object(raw_result, label=f"result {index}")
        allowed = {"run_id", "config_sha256", "metrics", "artifact_uri"}
        unknown = sorted(set(result) - allowed)
        if unknown:
            raise ResultValidationError(
                f"result {index} contains unsupported fields: {', '.join(unknown)}"
            )
        run_id = result.get("run_id")
        if not isinstance(run_id, str) or not run_id:
            raise ResultValidationError(f"result {index} has an invalid run_id")
        if run_id not in expected:
            raise ResultValidationError(
                f"result {index} references unknown run_id: {run_id}"
            )
        if run_id in seen:
            raise ResultValidationError(f"duplicate result for run_id: {run_id}")
        seen.add(run_id)
        run = expected[run_id]
        if result.get("config_sha256") != run.config_sha256:
            raise ResultValidationError(
                f"control/config drift for {run_id}: config_sha256 does not match the plan"
            )
        metrics = result.get("metrics")
        if not isinstance(metrics, Mapping) or not metrics:
            raise ResultValidationError(
                f"result {index} metrics must be a non-empty object"
            )
        _validate_aggregate_metrics(metrics, index)
        artifact_uri = result.get("artifact_uri")
        if not isinstance(artifact_uri, str) or not artifact_uri.strip():
            raise ResultValidationError(
                f"result {index} requires a non-empty artifact_uri"
            )
        merged.append(
            {
                "run_id": run_id,
                "config_sha256": run.config_sha256,
                "metrics": dict(metrics),
                "artifact_uri": artifact_uri,
            }
        )
    missing = sorted(set(expected) - seen)
    if missing:
        raise ResultValidationError("missing runs: " + ", ".join(missing))
    return {
        "experiment": "m2.5-chunking-ablation",
        "status": "MERGED",
        "run_count": len(merged),
        "controls": plan.controls,
        "runs": merged,
    }


def load_json_records(path: Path) -> list[dict[str, JSONValue]]:
    """Load a JSON array/object or JSONL without accepting source-bearing data."""

    raw = path.read_text(encoding="utf-8-sig")
    try:
        document = json.loads(raw)
    except json.JSONDecodeError:
        records: list[dict[str, JSONValue]] = []
        for line_number, line in enumerate(raw.splitlines(), start=1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ExperimentError(f"JSONL line {line_number} must be an object")
            records.append(value)
        return records
    if isinstance(document, list):
        if not all(isinstance(item, dict) for item in document):
            raise ExperimentError("JSON array entries must be objects")
        return list(document)
    if isinstance(document, dict):
        for key in ("runs", "results"):
            value = document.get(key)
            if isinstance(value, list) and all(
                isinstance(item, dict) for item in value
            ):
                return list(value)
    raise ExperimentError(
        "input must be a JSON object with runs/results or an object JSONL"
    )


def load_plan(path: Path) -> ExperimentPlan:
    document = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(document, dict) or not isinstance(
        document.get("controls"), Mapping
    ):
        raise ExperimentError("plan must contain a controls object")
    plan = build_plan(document["controls"])
    supplied_runs = document.get("runs")
    if not isinstance(supplied_runs, list) or len(supplied_runs) != len(plan.runs):
        raise ExperimentError("plan run matrix is incomplete or has an unexpected size")
    for supplied, expected in zip(supplied_runs, plan.runs, strict=True):
        if not isinstance(supplied, Mapping):
            raise ExperimentError("plan runs must be objects")
        if (
            supplied.get("run_id") != expected.run_id
            or supplied.get("config_sha256") != expected.config_sha256
        ):
            raise ExperimentError(
                f"plan run does not match deterministic configuration: {expected.run_id}"
            )
    return plan


def _validate_aggregate_metrics(metrics: Mapping[str, JSONValue], index: int) -> None:
    _reject_sensitive_keys(metrics, path=f"result {index}.metrics")
    for name, value in metrics.items():
        if not isinstance(name, str) or not name.strip():
            raise ResultValidationError(f"result {index} has an invalid metric name")
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise ResultValidationError(f"metric {name!r} must be a numeric aggregate")


def _reject_sensitive_keys(value: object, *, path: str) -> None:
    if isinstance(value, Mapping):
        for key, nested in value.items():
            key_text = str(key).lower()
            if _is_forbidden_key(key_text):
                raise ResultValidationError(
                    f"source/content field is forbidden: {path}.{key}"
                )
            _reject_sensitive_keys(nested, path=f"{path}.{key}")
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            _reject_sensitive_keys(nested, path=f"{path}[{index}]")


def _is_forbidden_key(key: str) -> bool:
    return (
        any(part in key for part in _FORBIDDEN_KEY_PARTS)
        or key == "text"
        or key.endswith("_text")
        or key.startswith("text_")
    )


def _json_object(value: object, *, label: str) -> dict[str, JSONValue]:
    if not isinstance(value, Mapping):
        raise ExperimentError(f"{label} must be a JSON object")
    try:
        encoded = json.dumps(
            value, ensure_ascii=True, sort_keys=True, separators=(",", ":")
        )
        decoded = json.loads(encoded)
    except (TypeError, ValueError) as exc:
        raise ExperimentError(f"{label} must contain JSON-compatible values") from exc
    if not isinstance(decoded, dict):
        raise ExperimentError(f"{label} must be a JSON object")
    return decoded


def _assert_unique_run_ids(runs: Sequence[RunConfig]) -> None:
    ids = [run.run_id for run in runs]
    if len(ids) != len(set(ids)):
        raise ExperimentError("run_id values must be unique")


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _write_jsonl(path: Path, records: Sequence[Mapping[str, JSONValue]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(
            json.dumps(record, ensure_ascii=True, sort_keys=True) + "\n"
            for record in records
        ),
        encoding="utf-8",
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Plan and merge the source-text-free M2.5 ablation."
    )
    subparsers = parser.add_subparsers(dest="operation", required=True)
    plan_parser = subparsers.add_parser(
        "plan", help="write a deterministic 27-run plan"
    )
    plan_parser.add_argument("--controls-json", required=True, type=Path)
    plan_parser.add_argument("--output-json", required=True, type=Path)
    plan_parser.add_argument("--output-jsonl", required=True, type=Path)
    plan_parser.add_argument("--command-template")
    merge_parser = subparsers.add_parser(
        "merge", help="validate and merge aggregate run results"
    )
    merge_parser.add_argument("--plan", required=True, type=Path)
    merge_parser.add_argument("--results", required=True, type=Path)
    merge_parser.add_argument("--output-json", required=True, type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        if args.operation == "plan":
            controls_document = json.loads(
                args.controls_json.read_text(encoding="utf-8-sig")
            )
            if isinstance(controls_document, Mapping) and isinstance(
                controls_document.get("controls"), Mapping
            ):
                controls_document = controls_document["controls"]
            plan = build_plan(controls_document, command_template=args.command_template)
            _write_json(args.output_json, plan.as_dict())
            _write_jsonl(args.output_jsonl, [run.as_dict() for run in plan.runs])
        else:
            plan = load_plan(args.plan)
            results = load_json_records(args.results)
            _write_json(args.output_json, merge_results(plan, results))
        return 0
    except (OSError, json.JSONDecodeError, ExperimentError) as exc:
        print(f"chunking-ablation error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
