from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Derive a versioned M2 quality-gate configuration from a measured baseline."
    )
    parser.add_argument("--baseline-json", required=True, type=Path)
    parser.add_argument("--output-json", required=True, type=Path)
    parser.add_argument("--recall-tolerance", type=_unit_interval, default=0.02)
    parser.add_argument("--faithfulness-tolerance", type=_unit_interval, default=0.02)
    parser.add_argument("--false-abstain-tolerance", type=_unit_interval, default=0.03)
    args = parser.parse_args()
    try:
        raw = args.baseline_json.read_bytes()
        baseline = json.loads(raw.decode("utf-8-sig"))
        config = derive_threshold_config(
            baseline,
            baseline_sha256=hashlib.sha256(raw).hexdigest(),
            recall_tolerance=args.recall_tolerance,
            faithfulness_tolerance=args.faithfulness_tolerance,
            false_abstain_tolerance=args.false_abstain_tolerance,
        )
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(
            json.dumps(config, indent=2, ensure_ascii=True, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        print(f"threshold generation failed: {exc}")
        return 2
    return 0


def derive_threshold_config(
    baseline: object,
    *,
    baseline_sha256: str,
    recall_tolerance: float = 0.02,
    faithfulness_tolerance: float = 0.02,
    false_abstain_tolerance: float = 0.03,
) -> dict[str, object]:
    if not isinstance(baseline, dict):
        raise ValueError("baseline report must be a JSON object")
    metadata = baseline.get("metadata")
    metrics = baseline.get("metrics")
    if not isinstance(metadata, dict) or not isinstance(metrics, dict):
        raise ValueError("baseline report must contain metadata and metrics objects")
    if metadata.get("metadata_complete") is not True:
        raise ValueError("baseline metadata must be complete")
    recall = _number(metrics, "recall_at_10")
    false_abstain = _number(metrics, "false_abstain_rate")
    ragas = metrics.get("ragas")
    if not isinstance(ragas, dict):
        raise ValueError("baseline faithfulness is unavailable")
    faithfulness = ragas.get("faithfulness")
    if not isinstance(faithfulness, dict) or faithfulness.get("status") != "provided":
        raise ValueError("baseline faithfulness is unavailable")
    faithfulness_value = _number(faithfulness, "value")
    return {
        "config_version": "m2.8-v1",
        "derived_from": {
            "baseline_sha256": baseline_sha256,
            "eval_set_version": metadata.get("eval_set_version"),
            "code_commit": metadata.get("code_commit"),
            "model_version": metadata.get("model_version"),
            "tolerances": {
                "recall_at_10": recall_tolerance,
                "faithfulness": faithfulness_tolerance,
                "false_abstain_rate": false_abstain_tolerance,
            },
        },
        "metrics": {
            "citation_validity": {"min": 0.95},
            "faithfulness": {"min": max(0.0, faithfulness_value - faithfulness_tolerance)},
            "false_abstain_rate": {"max": min(1.0, false_abstain + false_abstain_tolerance)},
            "recall@10": {"min": max(0.0, recall - recall_tolerance)},
            "unauthorized_violations": {"max": 0.0},
        },
    }


def _number(document: dict[str, Any], key: str) -> float:
    value = document.get(key)
    if isinstance(value, int | float) and not isinstance(value, bool):
        return float(value)
    raise ValueError(f"baseline metric is unavailable: {key}")


def _unit_interval(value: str) -> float:
    parsed = float(value)
    if not 0.0 <= parsed <= 1.0:
        raise argparse.ArgumentTypeError("value must be between 0 and 1")
    return parsed


if __name__ == "__main__":
    raise SystemExit(main())
