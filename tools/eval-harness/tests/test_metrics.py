from __future__ import annotations

import copy
import json
import math
from typing import Any

import pytest

from eval_harness.cli import build_report, compute_metrics


def test_metrics_report_recall_at_5_and_10_separately() -> None:
    records: list[dict[str, Any]] = [
        {
            "citations": [{"valid": True}, {"valid": False}],
            "expected_behavior": "answer",
            "abstained": False,
            "relevant_ranks": [2],
        },
        {
            "citations": [],
            "expected_behavior": "abstain",
            "abstained": True,
            "relevant_ranks": [8],
        },
    ]

    metrics = compute_metrics(records)

    assert metrics.citation_validity == 0.5
    assert metrics.abstain_when_expected == 1.0
    assert metrics.false_abstain_rate == 0.0
    assert metrics.unauthorized_violations == 0
    assert metrics.recall_at_5 == 0.5
    assert metrics.recall_at_10 == 1.0
    assert metrics.recall_at_k == metrics.recall_at_10
    assert metrics.mrr == 0.3125
    assert metrics.precision_at_8 == 0.125
    assert metrics.ndcg_at_8 == pytest.approx(
        (1.0 / math.log2(3) + 1.0 / math.log2(9)) / 2.0
    )


def test_precision_and_ndcg_at_8_use_fixed_k_and_optional_relevant_count() -> None:
    metrics = compute_metrics([{"relevant_ranks": [1, 9], "relevant_count": 3}])

    assert metrics.precision_at_8 == 1 / 8
    ideal_dcg = 1.0 + 1.0 / math.log2(3) + 1.0 / math.log2(4)
    assert metrics.ndcg_at_8 == pytest.approx(1.0 / ideal_dcg)


def test_character_ranges_are_used_to_derive_relevant_ranks() -> None:
    metrics = compute_metrics(
        [
            {
                "supporting_spans": [
                    {"document_version_id": "doc-1", "char_start": 10, "char_end": 20}
                ],
                "relevant_ranks": [1],
                "retrieved_chunks": [
                    {"document_version_id": "doc-1", "source_char_start": 0, "source_char_end": 10},
                    {
                        "document_version_id": "doc-1",
                        "source_char_start": 19,
                        "source_char_end": 30,
                    },
                ],
            }
        ]
    )

    assert metrics.recall_at_5 == 1.0
    assert metrics.recall_at_10 == 1.0
    assert metrics.mrr == 0.5
    assert metrics.precision_at_8 == 1 / 8
    assert metrics.ndcg_at_8 == pytest.approx(1.0 / math.log2(3))


def test_report_has_mandatory_metadata_and_safe_worst_five() -> None:
    records = [
        {
            "id": "q1",
            "category": "guide",
            "question": "private question must not appear in safe diagnostics",
            "answer": "private answer",
            "prompt": "private prompt",
            "candidate_text": "private candidate text",
            "citations": [{"valid": False, "evidence": "private citation evidence"}],
            "supporting_spans": [{"text": "private supporting evidence"}],
            "retrieved_chunks": [{"text": "private retrieved chunk text"}],
        }
    ]
    original_records = copy.deepcopy(records)
    payload = build_report(
        records,
        split="dev",
        quick=True,
        input_sha256="abc",
    )

    assert {
        "eval_set_version",
        "code_commit",
        "model_name",
        "model_version",
        "random_seed",
        "judge_model",
    } <= payload["metadata"].keys()
    assert payload["worst_cases"] == [
        {
            "id": "q1",
            "category": "guide",
            "metric_failures": ["citation_validity", "recall_at_10", "recall_at_5"],
        }
    ]
    assert records == original_records
    safe_results = payload["results"]
    assert safe_results[0]["id"] == "q1"
    assert safe_results[0]["category"] == "guide"
    assert safe_results[0]["relevant_ranks"] == []
    assert "precision_at_8" in safe_results[0]["metrics"]
    report_json = json.dumps(payload)
    for sensitive_text in (
        "private question",
        "private answer",
        "private prompt",
        "private candidate text",
        "private retrieved chunk text",
        "private supporting evidence",
        "private citation evidence",
    ):
        assert sensitive_text not in report_json
    assert payload["category_metrics"]["guide"]["count"] == 1
    assert payload["metrics"]["ragas"]["faithfulness"]["status"] == "unavailable"
