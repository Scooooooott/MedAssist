from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import asdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .gating import GateConfigError, evaluate_gate, load_threshold_config
from .holdout import (
    HoldoutError,
    assert_holdout_available,
    load_holdout_metadata,
    mark_holdout_consumed,
)
from .metadata import RunMetadata, resolve_metadata
from .metrics import (
    Metrics,
    category_metrics,
    ragas_metrics,
    safe_record_summary,
    worst_cases,
)
from .metrics import (
    compute_metrics as _compute_metrics,
)
from .persistence import PersistenceError, persist_report

EXIT_OK = 0
EXIT_GATE_FAILED = 1
EXIT_INPUT_ERROR = 2


class EvalHarnessError(ValueError):
    pass


def main() -> int:
    parser = _build_parser()
    args = parser.parse_args()
    try:
        raw = args.input.read_bytes()
        records = _read_records(raw)
        if args.quick:
            records = records[:30]
        metadata = resolve_metadata(records, _metadata_overrides(args))
        if args.gate and metadata.missing_fields():
            raise EvalHarnessError(
                "quality gate requires complete metadata; missing: "
                + ", ".join(metadata.missing_fields())
            )
        if args.threshold_config is not None and not args.gate:
            raise EvalHarnessError("--threshold-config requires --gate")
        holdout_state: dict[str, object] | None = None
        holdout_document: dict[str, Any] | None = None
        if args.split == "holdout":
            if not args.confirm_holdout:
                raise EvalHarnessError("holdout evaluation requires --confirm-holdout")
            if not args.holdout_version or not args.holdout_metadata:
                raise EvalHarnessError(
                    "holdout evaluation requires --holdout-version and --holdout-metadata"
                )
            holdout_document = load_holdout_metadata(args.holdout_metadata)
            holdout_state = assert_holdout_available(
                holdout_document, args.holdout_version, metadata.eval_set_version
            )
        payload = build_report(
            records,
            split=args.split,
            quick=args.quick,
            input_sha256=hashlib.sha256(raw).hexdigest(),
            metadata=metadata,
            holdout=holdout_state,
        )
        gate_failed = False
        if args.gate:
            if args.threshold_config is None:
                raise EvalHarnessError("--gate requires --threshold-config")
            rules, config_sha256 = load_threshold_config(args.threshold_config)
            gate = evaluate_gate(payload["metrics"], rules, config_sha256)
            payload["gate"] = gate.as_dict()
            gate_failed = gate.status == "failed"
            if gate_failed:
                print(
                    _gate_failure_message(gate.as_dict(), payload["worst_cases"]),
                    file=sys.stderr,
                )
        if args.mark_holdout_consumed:
            if args.split != "holdout" or holdout_document is None or args.holdout_metadata is None:
                raise EvalHarnessError(
                    "--mark-holdout-consumed requires a confirmed holdout evaluation"
                )
            if gate_failed:
                raise EvalHarnessError("a failed quality gate cannot consume a holdout subset")
            holdout_state = mark_holdout_consumed(
                args.holdout_metadata,
                holdout_document,
                args.holdout_version,
                metadata.code_commit,
                metadata.model_version,
            )
            payload["metadata"]["holdout"] = holdout_state
        _write_outputs(payload, args.output_json, args.output_md)
        if args.postgres_dsn:
            persist_report(payload, args.postgres_dsn)
        return EXIT_GATE_FAILED if gate_failed else EXIT_OK
    except (
        OSError,
        UnicodeDecodeError,
        json.JSONDecodeError,
        EvalHarnessError,
        GateConfigError,
        HoldoutError,
        PersistenceError,
        ValueError,
    ) as exc:
        print(f"eval-harness error: {exc}", file=sys.stderr)
        return EXIT_INPUT_ERROR


def compute_metrics(records: list[dict[str, Any]]) -> Metrics:
    return _compute_metrics(records)


def build_report(
    records: list[dict[str, Any]],
    *,
    split: str,
    quick: bool,
    input_sha256: str,
    metadata: RunMetadata | None = None,
    holdout: dict[str, object] | None = None,
) -> dict[str, Any]:
    resolved_metadata = metadata or resolve_metadata(records)
    metrics = _compute_metrics(records)
    report_metadata: dict[str, object] = {
        "generated_at": datetime.now(UTC).isoformat(),
        "input_sha256": input_sha256,
        "split": split,
        "quick": quick,
        "record_count": len(records),
        **resolved_metadata.as_dict(),
        "metadata_complete": not resolved_metadata.missing_fields(),
    }
    if holdout is not None:
        report_metadata["holdout"] = holdout
    return {
        "metadata": report_metadata,
        "metrics": {**asdict(metrics), "ragas": ragas_metrics(records)},
        "category_metrics": category_metrics(records),
        "worst_cases": worst_cases(records),
        "results": [safe_record_summary(record, index) for index, record in enumerate(records)],
    }


def render_markdown(payload: dict[str, Any]) -> str:
    metadata = payload["metadata"]
    metrics = payload["metrics"]
    rows = "\n".join(
        f"| {name} | {value:.4f} |"
        for name, value in metrics.items()
        if isinstance(value, int | float) and not isinstance(value, bool)
    )
    category_rows = "\n".join(
        f"| {category} | {values['count']} |"
        for category, values in payload["category_metrics"].items()
    )
    worst_rows = (
        "\n".join(
            f"| {item['id']} | {item['category']} | {', '.join(item['metric_failures'])} |"
            for item in payload["worst_cases"]
        )
        or "| None | | |"
    )
    gate = payload.get("gate")
    gate_text = f"\nGate: `{gate['status']}`\n" if isinstance(gate, dict) else ""
    return f"""# Evaluation Report

Split: `{metadata["split"]}`
Quick: `{metadata["quick"]}`
Records: {metadata["record_count"]}
Eval set: `{metadata["eval_set_version"]}`
Code commit: `{metadata["code_commit"]}`
Model: `{metadata["model_name"]}@{metadata["model_version"]}`
Judge: `{metadata["judge_model"]}`
Input SHA-256: `{metadata["input_sha256"]}`
{gate_text}
## Metrics

| Metric | Value |
|---|---:|
{rows}

RAGAS values are read from input records only; unavailable metrics are never fabricated.

## By Category

| Category | Count |
|---|---:|
{category_rows or "| None | 0 |"}

## Worst 10 Safe Diagnostics

| ID | Category | Metric failures |
|---|---|---|
{worst_rows}
"""


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run MedAssist retrieval and citation metrics.")
    parser.add_argument("--input", required=True, type=Path, help="JSONL evaluation result file.")
    parser.add_argument("--output-json", required=True, type=Path)
    parser.add_argument("--output-md", required=True, type=Path)
    parser.add_argument("--split", choices=["dev", "holdout"], default="dev")
    parser.add_argument("--confirm-holdout", action="store_true")
    parser.add_argument("--holdout-version")
    parser.add_argument("--holdout-metadata", type=Path)
    parser.add_argument("--mark-holdout-consumed", action="store_true")
    parser.add_argument("--quick", action="store_true", help="Evaluate only the first 30 records.")
    parser.add_argument("--gate", "--quality-gate", action="store_true", dest="gate")
    parser.add_argument("--threshold-config", type=Path)
    parser.add_argument(
        "--postgres-dsn", help="Explicit PostgreSQL DSN; persistence is otherwise disabled."
    )
    parser.add_argument(
        "--metadata-json", type=Path, help="JSON object containing run metadata overrides."
    )
    for name, value_type in (
        ("eval-set-version", str),
        ("code-commit", str),
        ("model-name", str),
        ("model-version", str),
        ("random-seed", int),
        ("judge-model", str),
    ):
        parser.add_argument(f"--{name}", type=value_type)
    return parser


def _read_records(raw: bytes) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(raw.decode("utf-8-sig").splitlines(), start=1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise EvalHarnessError(f"input line {line_number} must contain a JSON object")
        records.append(value)
    return records


def _metadata_overrides(args: argparse.Namespace) -> dict[str, object]:
    overrides: dict[str, object] = {}
    if args.metadata_json:
        document = json.loads(args.metadata_json.read_text(encoding="utf-8-sig"))
        if not isinstance(document, dict):
            raise EvalHarnessError("--metadata-json must contain a JSON object")
        overrides.update(document.get("metadata", document))
    names = (
        "eval_set_version",
        "code_commit",
        "model_name",
        "model_version",
        "random_seed",
        "judge_model",
    )
    for name in names:
        value = getattr(args, name)
        if value is not None:
            overrides[name] = value
    return overrides


def _write_outputs(payload: dict[str, Any], output_json: Path, output_md: Path) -> None:
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(
        json.dumps(payload, indent=2, ensure_ascii=True, sort_keys=True) + "\n", encoding="utf-8"
    )
    output_md.write_text(render_markdown(payload), encoding="utf-8")


def _gate_failure_message(gate: dict[str, object], worst_case_items: object) -> str:
    failures = gate.get("failures", [])
    worst_five = worst_case_items[:5] if isinstance(worst_case_items, list) else []
    return "quality gate failed: " + json.dumps(
        {"failures": failures, "worst_cases": worst_five},
        ensure_ascii=True,
        sort_keys=True,
    )


if __name__ == "__main__":
    raise SystemExit(main())
