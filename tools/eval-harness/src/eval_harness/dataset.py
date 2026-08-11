from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

CATEGORY_RATIOS = {
    "guideline_fact": 0.40,
    "clinical_record": 0.20,
    "structured_aggregation": 0.15,
    "no_answer": 0.15,
    "adversarial": 0.10,
}
REQUIRED_FIELDS = {
    "id",
    "question",
    "supporting_spans",
    "category",
    "difficulty",
    "split",
    "eval_set_version",
}
HOLDOUT_VERSION_PATTERN = re.compile(r"^holdout-v[1-9][0-9]*$")


@dataclass(frozen=True)
class DatasetValidation:
    errors: list[str]

    @property
    def valid(self) -> bool:
        return not self.errors

    def as_dict(self) -> dict[str, object]:
        return {"valid": self.valid, "error_count": len(self.errors), "errors": self.errors}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError as exc:
        raise ValueError(f"unable to read dataset: {path}") from exc
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON at line {line_number}") from exc
        if not isinstance(record, dict):
            raise ValueError(f"dataset line {line_number} must contain a JSON object")
        records.append(record)
    return records


def validate_dataset(
    records: list[dict[str, Any]],
    *,
    expected_total: int | None = None,
    metadata: dict[str, Any] | None = None,
) -> DatasetValidation:
    errors: list[str] = []
    total = len(records)
    if expected_total is not None and total != expected_total:
        errors.append(f"record_count: expected {expected_total}, got {total}")
    _validate_categories(records, errors)
    _validate_records(records, errors)
    _validate_split(records, errors, expected_total)
    _validate_version(records, errors)
    if any(record.get("split") == "holdout" for record in records):
        if metadata is None:
            errors.append("holdout_metadata: required when dataset contains holdout records")
        else:
            _validate_holdout_metadata(records, metadata, errors)
    elif metadata is not None:
        _validate_holdout_metadata(records, metadata, errors)
    return DatasetValidation(errors)


def _validate_categories(records: list[dict[str, Any]], errors: list[str]) -> None:
    counts = {category: 0 for category in CATEGORY_RATIOS}
    for record in records:
        category = record.get("category")
        if category in counts:
            counts[category] += 1
    for category, ratio in CATEGORY_RATIOS.items():
        expected = len(records) * ratio
        if abs(counts[category] - expected) > 2:
            errors.append(
                f"category_ratio: {category} has {counts[category]}, expected {expected:.2f} +/- 2"
            )


def _validate_records(records: list[dict[str, Any]], errors: list[str]) -> None:
    seen_ids: set[object] = set()
    versions: set[object] = set()
    for index, record in enumerate(records):
        record_id = record.get("id", index)
        prefix = f"record[{record_id}]"
        missing = sorted(REQUIRED_FIELDS - record.keys())
        if "expected_answer" not in record and "expected_behavior" not in record:
            missing.append("expected_answer|expected_behavior")
        if missing:
            errors.append(f"{prefix}: missing {', '.join(missing)}")
        if record_id in seen_ids:
            errors.append(f"{prefix}: duplicate id")
        seen_ids.add(record_id)
        version = record.get("eval_set_version")
        if version is not None:
            versions.add(version)
        spans = record.get("supporting_spans")
        if not isinstance(spans, list):
            errors.append(f"{prefix}: supporting_spans must be a list")
            continue
        if record.get("expected_behavior") == "answer" and not spans:
            errors.append(f"{prefix}: answer records require supporting_spans")
        for span_index, span in enumerate(spans):
            _validate_span(span, f"{prefix}.supporting_spans[{span_index}]", errors)
    if len(versions) > 1:
        errors.append("eval_set_version: all records must use one version")


def _validate_span(span: object, prefix: str, errors: list[str]) -> None:
    if not isinstance(span, dict):
        errors.append(f"{prefix}: must be an object")
        return
    if any("chunk" in key.lower() for key in span):
        errors.append(f"{prefix}: chunk IDs are forbidden; use source character ranges")
    required = {"document_version_id", "char_start", "char_end"}
    missing = sorted(required - span.keys())
    if missing:
        errors.append(f"{prefix}: missing {', '.join(missing)}")
        return
    start, end = span["char_start"], span["char_end"]
    if not _is_nonnegative_int(start) or not _is_nonnegative_int(end) or int(end) <= int(start):
        errors.append(
            f"{prefix}: char_start/char_end must be non-negative integers with end > start"
        )


def _validate_split(
    records: list[dict[str, Any]], errors: list[str], expected_total: int | None
) -> None:
    splits = {record.get("split") for record in records}
    invalid = sorted(str(value) for value in splits - {"dev", "holdout"})
    if invalid:
        errors.append(f"split: invalid values {invalid}")
    holdout_count = sum(1 for record in records if record.get("split") == "holdout")
    expected_holdout = round(len(records) * 0.30)
    if expected_total in {200, 300}:
        expected_holdout = round(expected_total * 0.30)
    if holdout_count != expected_holdout:
        errors.append(f"split: holdout count {holdout_count}, expected {expected_holdout} (30%)")


def _validate_version(records: list[dict[str, Any]], errors: list[str]) -> None:
    versions = {record.get("eval_set_version") for record in records}
    if len(versions) == 1:
        return
    if not versions:
        errors.append("eval_set_version: no version found")


def _validate_holdout_metadata(
    records: list[dict[str, Any]], metadata: dict[str, Any], errors: list[str]
) -> None:
    versions = {record.get("eval_set_version") for record in records}
    if metadata.get("eval_set_version") not in versions:
        errors.append("holdout_metadata: eval_set_version does not match dataset")
    subsets = metadata.get("holdout_subsets", metadata.get("holdouts"))
    if not isinstance(subsets, list) or not subsets:
        errors.append("holdout_metadata: holdout_subsets must be a non-empty list")
        return
    holdout_ids = {record.get("id") for record in records if record.get("split") == "holdout"}
    mapped_ids: set[object] = set()
    seen_versions: set[str] = set()
    for subset in subsets:
        if not isinstance(subset, dict):
            errors.append("holdout_metadata: each subset must be an object")
            continue
        version = subset.get("version")
        if not isinstance(version, str) or not HOLDOUT_VERSION_PATTERN.fullmatch(version):
            errors.append(f"holdout_metadata: invalid version {version!r}")
        elif version in seen_versions:
            errors.append(f"holdout_metadata: duplicate version {version}")
        else:
            seen_versions.add(version)
        status = subset.get("status")
        if status not in {"available", "reserved", "consumed"}:
            errors.append(f"holdout_metadata: invalid status {status!r}")
        usage = subset.get("consumption_count", subset.get("usage_count", 0))
        usage_value = _nonnegative_int_value(usage)
        if usage_value is None:
            errors.append(f"holdout_metadata: {version!r} usage count must be non-negative integer")
        elif status == "consumed" and usage_value < 1:
            errors.append(f"holdout_metadata: consumed {version!r} must have usage count >= 1")
        elif status in {"available", "reserved"} and usage_value != 0:
            errors.append(f"holdout_metadata: unconsumed {version!r} must have usage count 0")
        record_ids = subset.get("record_ids", [])
        if not isinstance(record_ids, list):
            errors.append(f"holdout_metadata: {version!r} record_ids must be a list")
            continue
        for record_id in record_ids:
            if record_id in mapped_ids:
                errors.append(f"holdout_metadata: record {record_id!r} mapped more than once")
            mapped_ids.add(record_id)
            if record_id not in holdout_ids:
                errors.append(f"holdout_metadata: record {record_id!r} is not a holdout record")
    if mapped_ids != holdout_ids:
        errors.append("holdout_metadata: subsets must cover every holdout record exactly once")


def _is_nonnegative_int(value: object) -> bool:
    return _nonnegative_int_value(value) is not None


def _nonnegative_int_value(value: object) -> int | None:
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return value
    return None
