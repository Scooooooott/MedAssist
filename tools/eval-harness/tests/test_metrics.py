from eval_harness.cli import build_report, compute_metrics


def test_metrics_cover_citations_abstention_and_ranking() -> None:
    metrics = compute_metrics(
        [
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
                "relevant_ranks": [],
            },
        ]
    )

    assert metrics.citation_validity == 0.5
    assert metrics.abstain_when_expected == 1.0
    assert metrics.false_abstain_rate == 0.0
    assert metrics.unauthorized_violations == 0
    assert metrics.recall_at_k == 0.5
    assert metrics.mrr == 0.25


def test_report_contains_metadata_categories_and_worst_cases() -> None:
    payload = build_report(
        [{"id": "q1", "category": "guide", "citations": [{"valid": False}]}],
        split="dev",
        quick=True,
        input_sha256="abc",
    )

    assert payload["metadata"]["quick"] is True
    assert payload["results"][0]["id"] == "q1"
    assert payload["category_metrics"]["guide"]["count"] == 1
    assert payload["worst_cases"][0]["id"] == "q1"
    assert payload["metrics"]["ragas"]["faithfulness"]["status"] in {"unavailable", "provided"}
