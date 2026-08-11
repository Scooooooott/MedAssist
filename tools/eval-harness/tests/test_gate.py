from __future__ import annotations

import json
from pathlib import Path

import pytest

from eval_harness.gating import GateConfigError, evaluate_gate, load_threshold_config


def test_threshold_config_is_hashed_and_gate_reports_failures(tmp_path: Path) -> None:
    path = tmp_path / "thresholds.json"
    path.write_text(
        json.dumps(
            {
                "config_version": "m2.8-v1",
                "metrics": {"recall@10": {"min": 0.8}, "unauthorized_violations": {"max": 0}},
            }
        ),
        encoding="utf-8",
    )
    rules, digest = load_threshold_config(path)

    result = evaluate_gate({"recall_at_10": 0.7, "unauthorized_violations": 1}, rules, digest)

    assert result.status == "failed"
    assert {failure["metric"] for failure in result.failures} == {
        "recall@10",
        "unauthorized_violations",
    }
    assert len(digest) == 64


def test_unavailable_ragas_metric_fails_gate_without_fabricating_a_value(tmp_path: Path) -> None:
    path = tmp_path / "thresholds.json"
    path.write_text(json.dumps({"metrics": {"faithfulness": {"min": 0.8}}}), encoding="utf-8")
    rules, digest = load_threshold_config(path)

    result = evaluate_gate(
        {"ragas": {"faithfulness": {"status": "unavailable", "value": None}}}, rules, digest
    )

    assert result.status == "failed"
    assert result.failures[0]["reason"] == "metric_unavailable"


def test_threshold_config_rejects_ambiguous_rule(tmp_path: Path) -> None:
    path = tmp_path / "thresholds.json"
    path.write_text(
        json.dumps({"metrics": {"recall_at_10": {"min": 0.8, "max": 0.9}}}), encoding="utf-8"
    )

    with pytest.raises(GateConfigError):
        load_threshold_config(path)


def test_precision_and_ndcg_rule_aliases_resolve_to_k8_metrics(tmp_path: Path) -> None:
    path = tmp_path / "thresholds.json"
    path.write_text(
        json.dumps(
            {
                "metrics": {
                    "precision@8": {"min": 0.1},
                    "nDCG@8": {"min": 0.8},
                }
            }
        ),
        encoding="utf-8",
    )
    rules, digest = load_threshold_config(path)

    result = evaluate_gate(
        {"precision_at_8": 0.125, "ndcg_at_8": 0.9},
        rules,
        digest,
    )

    assert result.status == "passed"
    assert result.failures == []
