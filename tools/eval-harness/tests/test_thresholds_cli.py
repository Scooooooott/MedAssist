from __future__ import annotations

import pytest

from eval_harness.thresholds_cli import derive_threshold_config


def _baseline() -> dict[str, object]:
    return {
        "metadata": {
            "metadata_complete": True,
            "eval_set_version": "golden-v2",
            "code_commit": "abc123",
            "model_version": "model-v1",
        },
        "metrics": {
            "recall_at_10": 0.82,
            "false_abstain_rate": 0.10,
            "ragas": {"faithfulness": {"status": "provided", "value": 0.88}},
        },
    }


def test_thresholds_are_derived_from_measured_baseline_with_tolerance() -> None:
    config = derive_threshold_config(_baseline(), baseline_sha256="a" * 64)

    metrics = config["metrics"]
    assert isinstance(metrics, dict)
    recall_rule = metrics["recall@10"]
    assert isinstance(recall_rule, dict)
    assert recall_rule["min"] == pytest.approx(0.80)
    assert metrics["faithfulness"] == {"min": 0.86}
    assert metrics["false_abstain_rate"] == {"max": 0.13}
    assert metrics["citation_validity"] == {"min": 0.95}
    assert metrics["unauthorized_violations"] == {"max": 0.0}


def test_threshold_generation_refuses_unmeasured_faithfulness() -> None:
    baseline = _baseline()
    metrics = baseline["metrics"]
    assert isinstance(metrics, dict)
    metrics["ragas"] = {"faithfulness": {"status": "unavailable", "value": None}}

    with pytest.raises(ValueError, match="faithfulness is unavailable"):
        derive_threshold_config(baseline, baseline_sha256="b" * 64)
