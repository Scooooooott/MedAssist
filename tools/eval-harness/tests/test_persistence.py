from __future__ import annotations

import sys
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from eval_harness.persistence import PersistenceError, persist_report


def _payload() -> dict[str, object]:
    return {
        "metadata": {
            "eval_set_version": "golden-v2",
            "split": "dev",
            "code_commit": "abc123",
            "model_name": "bge-m3",
            "model_version": "m2",
            "judge_model": "judge-1",
            "random_seed": 7,
            "question": "must never be persisted",
        },
        "metrics": {
            "recall_at_10": 0.8,
            "ragas": {"faithfulness": {"status": "provided", "value": 0.9}},
        },
        "results": [{"question": "raw question", "answer": "raw answer"}],
        "result_uri": "s3://reports/run.json",
    }


def _install_psycopg(monkeypatch: pytest.MonkeyPatch, cursor: MagicMock) -> None:
    connection = MagicMock()
    connection.__enter__.return_value = connection
    connection.cursor.return_value.__enter__.return_value = cursor
    module = SimpleNamespace(connect=MagicMock(return_value=connection))
    monkeypatch.setitem(sys.modules, "psycopg", module)


def test_inserts_only_flyway_columns_and_returns_uuid(monkeypatch: pytest.MonkeyPatch) -> None:
    cursor = MagicMock()
    cursor.fetchone.return_value = ("3b241101-e2bb-4255-8caf-4136c566a962",)
    _install_psycopg(monkeypatch, cursor)

    result = persist_report(_payload(), "postgresql://test")

    assert result == "3b241101-e2bb-4255-8caf-4136c566a962"
    sql, params = cursor.execute.call_args.args
    assert "CREATE" not in sql.upper()
    assert "ALTER" not in sql.upper()
    assert "evaluation_run" in sql
    assert "metadata" not in sql
    assert "results" not in sql
    assert params[0:7] == (
        "golden-v2",
        "dev",
        "abc123",
        "bge-m3",
        "m2",
        "judge-1",
        7,
    )
    assert '"question"' not in params[7]
    assert '"answer"' not in params[7]
    assert params[8] == "s3://reports/run.json"


def test_missing_flyway_table_is_explicit(monkeypatch: pytest.MonkeyPatch) -> None:
    cursor = MagicMock()

    class UndefinedTableError(Exception):
        sqlstate = "42P01"

    cursor.execute.side_effect = UndefinedTableError()
    _install_psycopg(monkeypatch, cursor)

    with pytest.raises(PersistenceError, match="Flyway-managed table evaluation_run is missing"):
        persist_report(_payload(), "postgresql://test")


def test_rejects_raw_records_and_does_not_connect(monkeypatch: pytest.MonkeyPatch) -> None:
    connect = MagicMock()
    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=connect))
    payload = _payload()
    payload["metrics"] = {"records": [{"question": "PHI-like raw value"}]}

    with pytest.raises(PersistenceError, match="aggregate numeric values only"):
        persist_report(payload, "postgresql://test")
    connect.assert_not_called()
