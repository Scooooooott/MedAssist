from __future__ import annotations

import json
from pathlib import Path

import pytest

from eval_harness.cli import EXIT_GATE_FAILED, EXIT_OK, main
from eval_harness.holdout import load_holdout_metadata, mark_holdout_consumed
from eval_harness.persistence import PersistenceError, persist_report


def test_holdout_requires_confirmation_and_consumed_metadata(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    input_path = tmp_path / "results.jsonl"
    input_path.write_text(json.dumps({"id": "q1", "relevant_ranks": [1]}) + "\n", encoding="utf-8")
    metadata_path = tmp_path / "holdout.json"
    metadata_path.write_text(
        json.dumps(
            {
                "eval_set_version": "unspecified",
                "holdout_subsets": [
                    {"version": "holdout-v1", "status": "consumed", "consumption_count": 1}
                ],
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setattr(
        "sys.argv",
        [
            "medassist-eval",
            "--input",
            str(input_path),
            "--output-json",
            str(tmp_path / "report.json"),
            "--output-md",
            str(tmp_path / "report.md"),
            "--split",
            "holdout",
            "--confirm-holdout",
            "--holdout-version",
            "holdout-v1",
            "--holdout-metadata",
            str(metadata_path),
        ],
    )

    assert main() == 2


def test_cli_quality_gate_preserves_outputs_and_returns_one_on_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    input_path = tmp_path / "results.jsonl"
    input_path.write_text(json.dumps({"id": "q1", "relevant_ranks": [11]}) + "\n", encoding="utf-8")
    threshold_path = tmp_path / "thresholds.json"
    threshold_path.write_text(
        json.dumps({"metrics": {"recall@10": {"min": 1.0}}}), encoding="utf-8"
    )
    output_json = tmp_path / "report.json"
    output_md = tmp_path / "report.md"
    monkeypatch.setattr(
        "sys.argv",
        [
            "medassist-eval",
            "--input",
            str(input_path),
            "--output-json",
            str(output_json),
            "--output-md",
            str(output_md),
            "--gate",
            "--threshold-config",
            str(threshold_path),
            "--eval-set-version",
            "golden-v2",
            "--code-commit",
            "abc123",
            "--model-name",
            "test-model",
            "--model-version",
            "v1",
            "--random-seed",
            "7",
            "--judge-model",
            "none",
        ],
    )

    assert main() == EXIT_GATE_FAILED
    assert output_json.exists()
    assert json.loads(output_json.read_text(encoding="utf-8"))["gate"]["status"] == "failed"
    assert output_md.exists()


def test_cli_legacy_report_still_returns_zero(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    input_path = tmp_path / "results.jsonl"
    input_path.write_text(json.dumps({"id": "q1", "relevant_ranks": [1]}) + "\n", encoding="utf-8")
    monkeypatch.setattr(
        "sys.argv",
        [
            "medassist-eval",
            "--input",
            str(input_path),
            "--output-json",
            str(tmp_path / "report.json"),
            "--output-md",
            str(tmp_path / "report.md"),
        ],
    )

    assert main() == EXIT_OK


def test_mark_holdout_consumed_records_metadata_for_future_rejection(tmp_path: Path) -> None:
    metadata_path = tmp_path / "holdout.json"
    metadata_path.write_text(
        json.dumps(
            {
                "eval_set_version": "golden-v2",
                "holdout_subsets": [
                    {
                        "version": "holdout-v2",
                        "status": "reserved",
                        "consumption_count": 0,
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    metadata = load_holdout_metadata(metadata_path)

    state = mark_holdout_consumed(metadata_path, metadata, "holdout-v2", "abc123", "model-v2")

    assert state["status"] == "consumed"
    assert load_holdout_metadata(metadata_path)["holdout_subsets"][0]["consumption_count"] == 1


def test_explicit_postgres_dsn_never_silently_discards_a_report() -> None:
    with pytest.raises(PersistenceError, match="PostgreSQL"):
        persist_report({"metadata": {}, "metrics": {}}, "postgresql://invalid")
