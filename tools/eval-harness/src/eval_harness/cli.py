from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Metrics:
    citation_validity: float
    abstain_when_expected: float
    false_abstain_rate: float
    unauthorized_violations: int
    recall_at_k: float
    mrr: float


RAGAS_NAMES = (
    "faithfulness",
    "answer_relevancy",
    "context_precision",
    "context_recall",
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run MedAssist retrieval and citation metrics.")
    parser.add_argument("--input", required=True, type=Path, help="JSONL evaluation result file.")
    parser.add_argument("--output-json", required=True, type=Path)
    parser.add_argument("--output-md", required=True, type=Path)
    parser.add_argument("--split", choices=["dev", "holdout"], default="dev")
    parser.add_argument("--confirm-holdout", action="store_true")
    parser.add_argument("--quick", action="store_true", help="Evaluate only the first 30 records.")
    args = parser.parse_args()

    if args.split == "holdout" and not args.confirm_holdout:
        raise SystemExit("holdout evaluation requires --confirm-holdout")

    raw = args.input.read_bytes()
    records = [
        json.loads(line)
        for line in raw.decode("utf-8-sig").splitlines()
        if line.strip()
    ]
    if args.quick:
        records = records[:30]
    payload = build_report(records, split=args.split, quick=args.quick, input_sha256=hashlib.sha256(raw).hexdigest())
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_md.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(payload, indent=2, ensure_ascii=True), encoding="utf-8")
    args.output_md.write_text(render_markdown(payload), encoding="utf-8")
    return 0


def compute_metrics(records: list[dict[str, Any]]) -> Metrics:
    if not records:
        return Metrics(0.0, 0.0, 0.0, 0, 0.0, 0.0)
    citation_total = 0
    citation_valid = 0
    expected_abstain_total = 0
    expected_abstain_correct = 0
    answerable_total = 0
    false_abstain = 0
    recall_hits = 0
    unauthorized_violations = 0
    reciprocal_ranks: list[float] = []
    for record in records:
        citations = record.get("citations", [])
        citation_total += len(citations)
        citation_valid += sum(1 for citation in citations if citation.get("valid") is True)
        expected_behavior = record.get("expected_behavior", "")
        abstained = bool(record.get("abstained", False))
        if expected_behavior == "abstain":
            expected_abstain_total += 1
            expected_abstain_correct += int(abstained)
        else:
            answerable_total += 1
            false_abstain += int(abstained)
        unauthorized_violations += int(record.get("unauthorized_violations", 0) or 0)
        relevant_ranks = [int(rank) for rank in record.get("relevant_ranks", []) if int(rank) > 0]
        if relevant_ranks:
            recall_hits += 1
            reciprocal_ranks.append(1.0 / min(relevant_ranks))
        else:
            reciprocal_ranks.append(0.0)
    return Metrics(
        citation_validity=safe_ratio(citation_valid, citation_total),
        abstain_when_expected=safe_ratio(expected_abstain_correct, expected_abstain_total),
        false_abstain_rate=safe_ratio(false_abstain, answerable_total),
        unauthorized_violations=unauthorized_violations,
        recall_at_k=safe_ratio(recall_hits, len(records)),
        mrr=sum(reciprocal_ranks) / len(reciprocal_ranks),
    )


def build_report(
    records: list[dict[str, Any]],
    *,
    split: str,
    quick: bool,
    input_sha256: str,
) -> dict[str, Any]:
    metrics = compute_metrics(records)
    return {
        "metadata": {
            "generated_at": datetime.now(UTC).isoformat(),
            "input_sha256": input_sha256,
            "split": split,
            "quick": quick,
            "record_count": len(records),
        },
        "metrics": {**asdict(metrics), "ragas": ragas_metrics(records)},
        "category_metrics": category_metrics(records),
        "worst_cases": worst_cases(records),
        "results": records,
    }


def ragas_metrics(records: list[dict[str, Any]]) -> dict[str, Any]:
    """Use supplied RAGAS values or explicitly report that the optional package is absent."""

    try:
        __import__("ragas")
    except ImportError:
        return {name: {"status": "unavailable", "value": None} for name in RAGAS_NAMES}
    values: dict[str, Any] = {}
    for name in RAGAS_NAMES:
        supplied = [record[name] for record in records if isinstance(record.get(name), (int, float))]
        values[name] = {
            "status": "provided" if supplied else "unavailable",
            "value": sum(supplied) / len(supplied) if supplied else None,
        }
    return values


def category_metrics(records: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for record in records:
        grouped.setdefault(str(record.get("category", "uncategorized")), []).append(record)
    return {
        category: {"count": len(items), "metrics": asdict(compute_metrics(items))}
        for category, items in sorted(grouped.items())
    }


def worst_cases(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    scored = []
    for index, record in enumerate(records):
        citations = record.get("citations", [])
        invalid = sum(1 for citation in citations if citation.get("valid") is not True)
        score = invalid + int(bool(record.get("abstained", False)) != (record.get("expected_behavior") == "abstain"))
        scored.append(
            {
                "id": record.get("id", index),
                "category": record.get("category", "uncategorized"),
                "score": score,
            }
        )
    return sorted(scored, key=lambda item: (-item["score"], str(item["id"])))[:10]


def safe_ratio(numerator: int, denominator: int) -> float:
    return 0.0 if denominator == 0 else numerator / denominator


def render_markdown(payload: dict[str, Any]) -> str:
    metrics = payload["metrics"]
    rows = "\n".join(
        f"| {name} | {value:.4f} |"
        for name, value in metrics.items()
        if isinstance(value, (int, float))
    )
    category_rows = "\n".join(
        f"| {category} | {values['count']} |"
        for category, values in payload["category_metrics"].items()
    )
    worst_rows = "\n".join(
        f"| {item['id']} | {item['category']} | {item['score']} |"
        for item in payload["worst_cases"]
    ) or "| None | | 0 |"
    return f"""# Evaluation Report

Split: `{payload['metadata']['split']}`
Quick: `{payload['metadata']['quick']}`
Records: {payload['metadata']['record_count']}
Input SHA-256: `{payload['metadata']['input_sha256']}`

## Metrics

| Metric | Value |
|---|---:|
{rows}

RAGAS status is recorded per metric in the JSON output; unavailable metrics are not fabricated.

## By Category

| Category | Count |
|---|---:|
{category_rows or '| None | 0 |'}

## Worst 10

| ID | Category | Score |
|---|---|---:|
{worst_rows}
"""


if __name__ == "__main__":
    raise SystemExit(main())
