from __future__ import annotations

import os
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

METADATA_FIELDS = (
    "eval_set_version",
    "code_commit",
    "model_name",
    "model_version",
    "random_seed",
    "judge_model",
)


@dataclass(frozen=True)
class RunMetadata:
    eval_set_version: str
    code_commit: str
    model_name: str
    model_version: str
    random_seed: int
    judge_model: str

    def as_dict(self) -> dict[str, object]:
        return {
            "eval_set_version": self.eval_set_version,
            "code_commit": self.code_commit,
            "model_name": self.model_name,
            "model_version": self.model_version,
            "random_seed": self.random_seed,
            "judge_model": self.judge_model,
        }

    def missing_fields(self) -> list[str]:
        values = self.as_dict()
        missing = [name for name in METADATA_FIELDS if not _is_present(values[name])]
        for name in ("code_commit", "model_name", "model_version", "judge_model"):
            if values[name] == "unknown":
                missing.append(name)
        return sorted(set(missing))


def resolve_metadata(
    records: Sequence[Mapping[str, Any]],
    overrides: Mapping[str, object] | None = None,
) -> RunMetadata:
    values: dict[str, object] = dict(overrides or {})
    values.setdefault(
        "eval_set_version", _single_record_value(records, "eval_set_version", "unspecified")
    )
    values.setdefault("code_commit", os.environ.get("GIT_COMMIT", "unknown"))
    values.setdefault("model_name", _single_record_value(records, "model_name", "unknown"))
    values.setdefault("model_version", _single_record_value(records, "model_version", "unknown"))
    values.setdefault("random_seed", _single_record_value(records, "random_seed", 0))
    values.setdefault("judge_model", _single_record_value(records, "judge_model", "unknown"))
    try:
        random_seed = _as_int(values["random_seed"])
    except (TypeError, ValueError) as exc:
        raise ValueError("random_seed must be an integer") from exc
    return RunMetadata(
        eval_set_version=_as_text(values["eval_set_version"]),
        code_commit=_as_text(values["code_commit"]),
        model_name=_as_text(values["model_name"]),
        model_version=_as_text(values["model_version"]),
        random_seed=random_seed,
        judge_model=_as_text(values["judge_model"]),
    )


def _single_record_value(
    records: Sequence[Mapping[str, Any]],
    field: str,
    default: object,
) -> object:
    values = {
        record[field] for record in records if field in record and record[field] not in (None, "")
    }
    return next(iter(values)) if len(values) == 1 else default


def _as_text(value: object) -> str:
    return str(value).strip()


def _is_present(value: object) -> bool:
    return isinstance(value, int) or bool(str(value).strip())


def _as_int(value: object) -> int:
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    if isinstance(value, str):
        return int(value)
    raise ValueError("random_seed must be an integer")
