from __future__ import annotations

import json
from pathlib import Path

import pytest

from deid_eval.cli import _read_jsonl, main, render_markdown
from deid_eval.metrics import EvaluationRecord, Span, evaluate_records, parse_record


def test_exact_metrics_direct_recall_confusion_and_missed_spans() -> None:
    records = (
        EvaluationRecord(
            document_id="doc-b",
            gold_spans=(Span("PERSON", 0, 5), Span("MRN", 10, 16), Span("SSN", 20, 29)),
            predicted_spans=(Span("PERSON", 0, 5), Span("ID", 10, 16)),
        ),
        EvaluationRecord(
            document_id="doc-a",
            gold_spans=(Span("PHONE", 2, 12),),
            predicted_spans=(Span("PHONE", 2, 12), Span("EMAIL", 30, 40)),
        ),
    )

    report = evaluate_records(records)

    assert report["overall"] == {
        "matched": 2,
        "predicted": 4,
        "gold": 4,
        "precision": 0.5,
        "recall": 0.5,
        "f1": 0.5,
    }
    assert report["by_entity_type"]["PERSON"]["recall"] == 1.0
    assert report["by_entity_type"]["ID"]["precision"] == 0.0
    assert report["direct_identifier_recall"]["overall"] == {
        "matched": 2,
        "gold": 4,
        "recall": 0.5,
    }
    assert report["type_confusion_matrix"] == {"MRN": {"ID": 1}}
    assert report["missed_spans"] == [
        {"document_id": "doc-b", "entity_type": "MRN", "start": 10, "end": 16},
        {"document_id": "doc-b", "entity_type": "SSN", "start": 20, "end": 29},
    ]


def test_safe_parser_rejects_source_text_and_duplicate_spans() -> None:
    safe = {
        "document_id": "doc-1",
        "gold_spans": [],
        "predicted_spans": [],
    }
    assert parse_record(safe, line_number=1).document_id == "doc-1"
    with pytest.raises(ValueError, match="unexpected fields: text"):
        parse_record({**safe, "text": "synthetic"}, line_number=1)
    with pytest.raises(ValueError, match="duplicate span"):
        parse_record(
            {
                "document_id": "doc-1",
                "gold_spans": [
                    {"entity_type": "PERSON", "start": 0, "end": 1},
                    {"entity_type": "PERSON", "start": 0, "end": 1},
                ],
                "predicted_spans": [],
            },
            line_number=1,
        )


def test_jsonl_reader_skips_blank_lines_without_echoing_content() -> None:
    raw = b'\n{"document_id":"doc-1","gold_spans":[],"predicted_spans":[]}\n'
    assert len(_read_jsonl(raw)) == 1


def test_deterministic_markdown_has_no_source_text() -> None:
    report = evaluate_records((EvaluationRecord("doc-1", (Span("PERSON", 0, 1),), ()),))
    report["metadata"]["input_sha256"] = "a" * 64
    markdown = render_markdown(report)
    assert markdown == render_markdown(report)
    assert "source text" in markdown
    assert "PERSON" in markdown
    assert "doc-1" in markdown
    assert "synthetic" not in markdown


def test_json_round_trip_is_deterministic() -> None:
    report = evaluate_records(())
    assert json.dumps(report, ensure_ascii=True, sort_keys=True) == json.dumps(
        report, ensure_ascii=True, sort_keys=True
    )


def test_cli_writes_deterministic_json_and_markdown_without_source_text(tmp_path: Path) -> None:
    input_path = tmp_path / "spans.jsonl"
    output_json = tmp_path / "report.json"
    output_md = tmp_path / "report.md"
    args = [
        "--input",
        str(input_path),
        "--output-json",
        str(output_json),
        "--output-md",
        str(output_md),
    ]
    input_path.write_text(
        json.dumps(
            {
                "document_id": "doc-1",
                "gold_spans": [{"entity_type": "PERSON", "start": 1, "end": 3}],
                "predicted_spans": [],
            }
        )
        + "\n",
        encoding="utf-8",
    )
    assert main(args) == 0
    first_json = output_json.read_text(encoding="utf-8")
    first_md = output_md.read_text(encoding="utf-8")
    assert main(args) == 0
    assert output_json.read_text(encoding="utf-8") == first_json
    assert output_md.read_text(encoding="utf-8") == first_md
    assert "synthetic" not in first_json
    assert "raw clinical text" not in first_md


def test_cli_direct_identifier_gate_uses_distinct_exit_code(tmp_path: Path) -> None:
    input_path = tmp_path / "spans.jsonl"
    output_json = tmp_path / "report.json"
    output_md = tmp_path / "report.md"
    input_path.write_text(
        json.dumps(
            {
                "document_id": "doc-1",
                "gold_spans": [{"entity_type": "PERSON", "start": 0, "end": 4}],
                "predicted_spans": [],
            }
        )
        + "\n",
        encoding="utf-8",
    )

    exit_code = main(
        [
            "--input",
            str(input_path),
            "--output-json",
            str(output_json),
            "--output-md",
            str(output_md),
            "--min-direct-identifier-recall",
            "0.95",
        ]
    )

    assert exit_code == 1
    report = json.loads(output_json.read_text(encoding="utf-8"))
    assert report["gate"]["status"] == "failed"
    assert report["gate"]["observed"] == 0.0
    assert "Gate: `failed`" in output_md.read_text(encoding="utf-8")


def test_duplicate_document_ids_are_rejected() -> None:
    record = EvaluationRecord("same", (), ())
    with pytest.raises(ValueError, match="duplicate document_id"):
        evaluate_records((record, record))
