from __future__ import annotations

import copy

import pytest

from scripts.experiments.chunking_ablation import (
    ExperimentError,
    MissingControlError,
    ResultValidationError,
    build_plan,
    detect_control_drift,
    merge_results,
    sha256_json,
)


@pytest.fixture
def controls() -> dict[str, object]:
    return {
        "source_corpus_version": "synthetic-fixture-v1",
        "eval_set_version": "golden-v2",
        "embedding_model": "bge-m3",
        "embedding_model_version": "m1-baseline",
        "embedding_dimension": 1024,
        "retrieval_mode": "hybrid",
        "context_mode": "OFF",
        "rerank_enabled": False,
        "rrf_k": 60,
        "candidate_top_n": 50,
        "final_top_k": 5,
        "filters": {"status": "ACTIVE"},
        "tokenizer": "cl100k_base-v1",
        "source_range_index_version": "m1.8-v1",
        "hardware": "ci-synthetic",
        "schema_version": "V3",
        "code_commit": "abc1234",
        "seed": 42,
    }


def test_plan_has_exactly_27_unique_matrix_runs(controls: dict[str, object]) -> None:
    plan = build_plan(controls)

    assert len(plan.runs) == 27
    assert len({run.run_id for run in plan.runs}) == 27
    assert {
        (run.strategy, run.target_tokens, run.overlap_tokens) for run in plan.runs
    } == {
        (strategy, target, overlap)
        for strategy in ("fixed", "structure", "semantic")
        for target in (256, 512, 1024)
        for overlap in (0, 50, 128)
    }


def test_plan_and_run_digests_are_stable(controls: dict[str, object]) -> None:
    first = build_plan(controls)
    second = build_plan(dict(reversed(list(controls.items()))))

    assert [run.as_dict() for run in first.runs] == [
        run.as_dict() for run in second.runs
    ]
    assert sha256_json({"b": 2, "a": 1}) == sha256_json({"a": 1, "b": 2})


def test_command_template_is_rendered_but_not_executed(
    controls: dict[str, object],
) -> None:
    plan = build_plan(
        controls,
        command_template="runner --run {run_id} --strategy {strategy} --target {targetTokens}",
    )

    assert plan.runs[0].command is not None
    assert "runner --run m25-fixed-256-0-" in plan.runs[0].command


def test_missing_control_is_rejected(controls: dict[str, object]) -> None:
    controls.pop("embedding_model_version")

    with pytest.raises(MissingControlError, match="embedding_model_version"):
        build_plan(controls)


def test_invalid_command_template_is_rejected(controls: dict[str, object]) -> None:
    with pytest.raises(ExperimentError, match="invalid command template"):
        build_plan(controls, command_template="runner {missing}")


def test_sensitive_result_fields_are_rejected(controls: dict[str, object]) -> None:
    plan = build_plan(controls)
    run = plan.runs[0]
    result = {
        "run_id": run.run_id,
        "config_sha256": run.config_sha256,
        "metrics": {"recall@10": 0.5},
        "artifact_uri": "s3://approved/m25/run.json",
        "answer": "must not be accepted",
    }

    with pytest.raises(ResultValidationError, match="unsupported fields"):
        merge_results(plan, [result])


def test_metrics_must_be_aggregate_numeric_values(controls: dict[str, object]) -> None:
    plan = build_plan(controls)
    run = plan.runs[0]
    result = {
        "run_id": run.run_id,
        "config_sha256": run.config_sha256,
        "metrics": {"retrieved_text": "source content"},
        "artifact_uri": "s3://approved/m25/run.json",
    }

    with pytest.raises(ResultValidationError, match="source/content field"):
        merge_results(plan, [result])


def test_merge_rejects_missing_and_duplicate_runs(controls: dict[str, object]) -> None:
    plan = build_plan(controls)
    run = plan.runs[0]
    result = {
        "run_id": run.run_id,
        "config_sha256": run.config_sha256,
        "metrics": {"recall@10": 0.5, "chunk_count": 100},
        "artifact_uri": "s3://approved/m25/run.json",
    }

    with pytest.raises(ResultValidationError, match="missing runs"):
        merge_results(plan, [result])
    with pytest.raises(ResultValidationError, match="duplicate"):
        merge_results(plan, [result, result])


def test_config_digest_detects_control_drift(controls: dict[str, object]) -> None:
    drifted = copy.deepcopy(controls)
    drifted["retrieval_mode"] = "vector"

    drift = detect_control_drift(controls, drifted)

    assert drift == {"retrieval_mode": ("hybrid", "vector")}


def test_merge_rejects_run_digest_drift(controls: dict[str, object]) -> None:
    plan = build_plan(controls)
    run = plan.runs[0]
    result = {
        "run_id": run.run_id,
        "config_sha256": "0" * 64,
        "metrics": {"recall@10": 0.5},
        "artifact_uri": "s3://approved/m25/run.json",
    }

    with pytest.raises(ResultValidationError, match="drift"):
        merge_results(plan, [result])
