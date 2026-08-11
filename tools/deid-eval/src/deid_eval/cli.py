from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

from deid_eval.metrics import EvaluationRecord, evaluate_records, parse_record


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Evaluate safe de-identification spans without source text."
    )
    parser.add_argument("--input", required=True, type=Path, help="Safe JSONL span annotations.")
    parser.add_argument("--output-json", required=True, type=Path)
    parser.add_argument("--output-md", required=True, type=Path)
    parser.add_argument(
        "--min-direct-identifier-recall",
        type=_unit_interval,
        help="Fail with exit code 1 when aggregate direct-identifier recall is below this value.",
    )
    args = parser.parse_args(argv)

    try:
        raw = args.input.read_bytes()
        records = _read_jsonl(raw)
        report = evaluate_records(records)
        report["metadata"]["input_sha256"] = hashlib.sha256(raw).hexdigest()
        gate_failed = _apply_gate(report, args.min_direct_identifier_recall)
        json_output = json.dumps(report, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
        markdown_output = render_markdown(report)
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_md.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(json_output, encoding="utf-8", newline="\n")
        args.output_md.write_text(markdown_output, encoding="utf-8", newline="\n")
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        print(f"deid evaluation failed: {exc}", file=sys.stderr)
        return 2
    return 1 if gate_failed else 0


def _unit_interval(value: str) -> float:
    parsed = float(value)
    if not 0.0 <= parsed <= 1.0:
        raise argparse.ArgumentTypeError("value must be between 0 and 1")
    return parsed


def _apply_gate(report: dict[str, Any], threshold: float | None) -> bool:
    if threshold is None:
        return False
    observed = float(report["direct_identifier_recall"]["overall"]["recall"])
    failed = observed < threshold
    report["gate"] = {
        "status": "failed" if failed else "passed",
        "metric": "direct_identifier_recall",
        "operator": "min",
        "threshold": threshold,
        "observed": observed,
    }
    return failed


def _read_jsonl(raw: bytes) -> tuple[EvaluationRecord, ...]:
    records: list[EvaluationRecord] = []
    for line_number, line in enumerate(raw.decode("utf-8-sig").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"line {line_number}: invalid JSON") from exc
        records.append(parse_record(value, line_number=line_number))
    return tuple(records)


def render_markdown(report: dict[str, Any]) -> str:
    metadata = report["metadata"]
    overall = report["overall"]
    lines: list[str] = [
        "# De-identification Baseline Evaluation",
        "",
        "This report contains span metadata only; source text is excluded by contract.",
        "",
        f"- Documents: {metadata['document_count']}",
        f"- Gold spans: {metadata['gold_span_count']}",
        f"- Predicted spans: {metadata['predicted_span_count']}",
        f"- Input SHA-256: `{metadata['input_sha256']}`",
    ]
    gate = report.get("gate")
    if isinstance(gate, dict):
        lines.append(
            f"- Gate: `{gate['status']}` "
            f"(`{gate['metric']}` {gate['operator']} {gate['threshold']}, "
            f"observed {gate['observed']:.6f})"
        )
    lines.extend(
        [
            "",
            "## Overall Exact-Span Metrics",
            "",
            "| Metric | Value |",
            "|---|---:|",
        ]
    )
    lines.extend(_metric_rows(overall))
    lines.extend(
        [
            "",
            "## By Entity Type",
            "",
            "| Entity type | Gold | Predicted | Matched | Precision | Recall | F1 |",
            "|---|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for entity_type, metrics in report["by_entity_type"].items():
        metric_row = (
            f"| {entity_type} | {metrics['gold']} | {metrics['predicted']} | "
            f"{metrics['matched']} | {metrics['precision']:.6f} | "
            f"{metrics['recall']:.6f} | {metrics['f1']:.6f} |"
        )
        lines.append(metric_row)
    lines.extend(
        [
            "",
            "## Direct-Identifier Recall",
            "",
            "Direct identifiers: PERSON, MRN, SSN, PHONE.",
            "",
            "| Entity type | Gold | Matched | Recall |",
            "|---|---:|---:|---:|",
        ]
    )
    for entity_type, metrics in report["direct_identifier_recall"].items():
        lines.append(
            f"| {entity_type} | {metrics['gold']} | "
            f"{metrics['matched']} | {metrics['recall']:.6f} |"
        )
    lines.extend(["", "## Type Confusion Matrix", ""])
    confusion = report["type_confusion_matrix"]
    if confusion:
        lines.extend(
            [
                "| Gold \\ Predicted | " + " | ".join(sorted(report["by_entity_type"])) + " |",
                "|---|" + "---:|" * len(report["by_entity_type"]),
            ]
        )
        for gold_type in sorted(report["by_entity_type"]):
            row = [
                str(confusion.get(gold_type, {}).get(predicted_type, 0))
                for predicted_type in sorted(report["by_entity_type"])
            ]
            lines.append(f"| {gold_type} | " + " | ".join(row) + " |")
    else:
        lines.append("No exact-range type confusions were observed.")
    lines.extend(
        [
            "",
            "## Missed Spans",
            "",
            "| Document ID | Entity type | Start | End |",
            "|---|---|---:|---:|",
        ]
    )
    missed = report["missed_spans"]
    if missed:
        lines.extend(
            f"| {item['document_id']} | {item['entity_type']} | {item['start']} | {item['end']} |"
            for item in missed
        )
    else:
        lines.append("| None | | | |")
    lines.append("")
    return "\n".join(lines)


def _metric_rows(metrics: dict[str, Any]) -> list[str]:
    return [
        f"| Matched | {metrics['matched']} |",
        f"| Gold | {metrics['gold']} |",
        f"| Predicted | {metrics['predicted']} |",
        f"| Precision | {metrics['precision']:.6f} |",
        f"| Recall | {metrics['recall']:.6f} |",
        f"| F1 | {metrics['f1']:.6f} |",
    ]


if __name__ == "__main__":
    raise SystemExit(main())
