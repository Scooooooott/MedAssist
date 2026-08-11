from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class GateConfigError(ValueError):
    pass


@dataclass(frozen=True)
class ThresholdRule:
    metric: str
    operator: str
    threshold: float


@dataclass(frozen=True)
class GateResult:
    status: str
    config_sha256: str
    failures: list[dict[str, object]]

    def as_dict(self) -> dict[str, object]:
        return {
            "status": self.status,
            "config_sha256": self.config_sha256,
            "failures": self.failures,
        }


def load_threshold_config(path: Path) -> tuple[list[ThresholdRule], str]:
    raw = path.read_bytes()
    try:
        document = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateConfigError(f"invalid threshold JSON: {path}") from exc
    if not isinstance(document, dict):
        raise GateConfigError("threshold config must be a JSON object")
    entries = document.get("metrics")
    if not isinstance(entries, dict) or not entries:
        raise GateConfigError("threshold config must contain a non-empty 'metrics' object")
    rules: list[ThresholdRule] = []
    for metric, value in sorted(entries.items()):
        if not isinstance(metric, str) or not metric.strip():
            raise GateConfigError("threshold metric names must be non-empty strings")
        if isinstance(value, int | float) and not isinstance(value, bool):
            rules.append(ThresholdRule(metric, "min", float(value)))
            continue
        if not isinstance(value, dict):
            raise GateConfigError(f"threshold for {metric} must be a number or object")
        operators = [operator for operator in ("min", "max") if operator in value]
        if len(operators) != 1 or not _is_number(value[operators[0]]):
            raise GateConfigError(
                f"threshold for {metric} must contain exactly one numeric min/max"
            )
        rules.append(ThresholdRule(metric, operators[0], float(value[operators[0]])))
    return rules, hashlib.sha256(raw).hexdigest()


def evaluate_gate(
    metrics: dict[str, Any], rules: list[ThresholdRule], config_sha256: str
) -> GateResult:
    failures: list[dict[str, object]] = []
    for rule in rules:
        value = _metric_value(metrics, rule.metric)
        if value is None:
            failures.append(
                {
                    "metric": rule.metric,
                    "reason": "metric_unavailable",
                    "operator": rule.operator,
                    "threshold": rule.threshold,
                }
            )
            continue
        passed = value >= rule.threshold if rule.operator == "min" else value <= rule.threshold
        if not passed:
            failures.append(
                {
                    "metric": rule.metric,
                    "reason": "threshold_failed",
                    "observed": value,
                    "operator": rule.operator,
                    "threshold": rule.threshold,
                }
            )
    return GateResult("passed" if not failures else "failed", config_sha256, failures)


def _metric_value(metrics: dict[str, Any], name: str) -> float | None:
    aliases = {
        "recall@5": "recall_at_5",
        "recall@10": "recall_at_10",
        "precision@8": "precision_at_8",
        "ndcg@8": "ndcg_at_8",
    }
    normalized_name = name.strip().lower()
    canonical = aliases.get(normalized_name, normalized_name)
    value = _as_float(metrics.get(canonical))
    if value is not None:
        return value
    ragas = metrics.get("ragas")
    if isinstance(ragas, dict):
        entry = ragas.get(canonical)
        if isinstance(entry, dict) and entry.get("status") == "provided":
            entry_value = entry.get("value")
            entry_float = _as_float(entry_value)
            if entry_float is not None:
                return entry_float
    return None


def _is_number(value: object) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def _as_float(value: object) -> float | None:
    if isinstance(value, int | float) and not isinstance(value, bool):
        return float(value)
    return None
