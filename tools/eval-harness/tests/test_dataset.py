from __future__ import annotations

from typing import Any

from eval_harness.dataset import validate_dataset


def _record(record_id: str, category: str, split: str = "dev") -> dict[str, Any]:
    return {
        "id": record_id,
        "question": "question",
        "expected_behavior": "answer",
        "supporting_spans": [{"document_version_id": "doc-1", "char_start": 10, "char_end": 20}],
        "category": category,
        "difficulty": "easy",
        "split": split,
        "eval_set_version": "golden-v2",
    }


def test_dataset_validator_checks_shape_and_holdout_metadata() -> None:
    records = [
        _record("q1", "guideline_fact", "holdout"),
        _record("q2", "clinical_record"),
    ]
    metadata = {
        "eval_set_version": "golden-v2",
        "holdout_subsets": [
            {
                "version": "holdout-v2",
                "status": "reserved",
                "record_ids": ["q1"],
                "consumption_count": 0,
            }
        ],
    }

    result = validate_dataset(records, metadata=metadata)

    assert result.valid
    assert not any(error.startswith("holdout_metadata:") for error in result.errors)


def test_dataset_validator_rejects_chunk_ids_and_bad_holdout_discipline() -> None:
    record = _record("q1", "guideline_fact", "holdout")
    record["supporting_spans"] = [
        {"document_version_id": "doc-1", "char_start": 10, "char_end": 20, "chunk_id": "forbidden"}
    ]
    metadata = {
        "eval_set_version": "golden-v2",
        "holdout_subsets": [
            {
                "version": "holdout-v1",
                "status": "consumed",
                "record_ids": ["q1"],
                "consumption_count": 0,
            }
        ],
    }

    result = validate_dataset([record], metadata=metadata)

    assert any("chunk IDs are forbidden" in error for error in result.errors)
    assert any("consumed" in error for error in result.errors)
