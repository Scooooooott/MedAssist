from __future__ import annotations

from collections import Counter
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

DIRECT_IDENTIFIER_TYPES = ("PERSON", "MRN", "SSN", "PHONE")
_RECORD_KEYS = frozenset(("document_id", "gold_spans", "predicted_spans"))
_SPAN_KEYS = frozenset(("entity_type", "start", "end"))


@dataclass(frozen=True, order=True)
class Span:
    """A source-text-free entity span using half-open offsets."""

    entity_type: str
    start: int
    end: int

    def as_dict(self) -> dict[str, Any]:
        return {"entity_type": self.entity_type, "start": self.start, "end": self.end}


@dataclass(frozen=True)
class EvaluationRecord:
    document_id: str
    gold_spans: tuple[Span, ...]
    predicted_spans: tuple[Span, ...]


def parse_record(value: object, *, line_number: int) -> EvaluationRecord:
    if not isinstance(value, dict):
        raise ValueError(f"line {line_number}: record must be a JSON object")
    keys = frozenset(value)
    if keys != _RECORD_KEYS:
        unexpected = sorted(keys - _RECORD_KEYS)
        missing = sorted(_RECORD_KEYS - keys)
        details: list[str] = []
        if unexpected:
            details.append(f"unexpected fields: {', '.join(unexpected)}")
        if missing:
            details.append(f"missing fields: {', '.join(missing)}")
        raise ValueError(f"line {line_number}: invalid safe record ({'; '.join(details)})")

    document_id = value["document_id"]
    if not isinstance(document_id, str) or not document_id.strip():
        raise ValueError(f"line {line_number}: document_id must be a non-empty string")
    return EvaluationRecord(
        document_id=document_id,
        gold_spans=_parse_spans(value["gold_spans"], line_number=line_number, field="gold_spans"),
        predicted_spans=_parse_spans(
            value["predicted_spans"], line_number=line_number, field="predicted_spans"
        ),
    )


def _parse_spans(value: object, *, line_number: int, field: str) -> tuple[Span, ...]:
    if not isinstance(value, list):
        raise ValueError(f"line {line_number}: {field} must be a JSON array")
    spans: list[Span] = []
    seen: set[Span] = set()
    seen_ranges: set[tuple[int, int]] = set()
    for index, item in enumerate(value):
        if not isinstance(item, dict) or frozenset(item) != _SPAN_KEYS:
            raise ValueError(f"line {line_number}: {field}[{index}] must contain only span fields")
        entity_type = item["entity_type"]
        start = item["start"]
        end = item["end"]
        if not isinstance(entity_type, str) or not entity_type.strip():
            raise ValueError(f"line {line_number}: {field}[{index}].entity_type must be non-empty")
        if isinstance(start, bool) or not isinstance(start, int):
            raise ValueError(f"line {line_number}: {field}[{index}].start must be an integer")
        if isinstance(end, bool) or not isinstance(end, int):
            raise ValueError(f"line {line_number}: {field}[{index}].end must be an integer")
        span = Span(entity_type.strip().upper(), start, end)
        if span.start < 0 or span.end <= span.start:
            raise ValueError(f"line {line_number}: {field}[{index}] has invalid offsets")
        if span in seen:
            raise ValueError(f"line {line_number}: duplicate span in {field}")
        if (span.start, span.end) in seen_ranges:
            raise ValueError(f"line {line_number}: duplicate range in {field}")
        seen.add(span)
        seen_ranges.add((span.start, span.end))
        spans.append(span)
    return tuple(spans)


def evaluate_records(records: Iterable[EvaluationRecord]) -> dict[str, Any]:
    """Return a deterministic, source-text-free evaluation report."""

    materialized = tuple(records)
    exact_counts: Counter[tuple[str, str]] = Counter()
    types: set[str] = set()
    confusion: Counter[tuple[str, str]] = Counter()
    missed: list[dict[str, Any]] = []
    direct_counts: Counter[str] = Counter()
    document_ids: set[str] = set()

    for record in materialized:
        if record.document_id in document_ids:
            raise ValueError(f"duplicate document_id: {record.document_id}")
        document_ids.add(record.document_id)
        gold_by_range = {(span.start, span.end): span for span in record.gold_spans}
        predicted_by_range = {(span.start, span.end): span for span in record.predicted_spans}
        gold_set = set(record.gold_spans)
        predicted_set = set(record.predicted_spans)
        exact = gold_set & predicted_set
        for span in record.gold_spans:
            types.add(span.entity_type)
            if span.entity_type in DIRECT_IDENTIFIER_TYPES:
                direct_counts[f"gold_{span.entity_type}"] += 1
            if span not in exact:
                missed.append({"document_id": record.document_id, **span.as_dict()})
        for span in record.predicted_spans:
            types.add(span.entity_type)
        for span in exact:
            exact_counts[("tp", span.entity_type)] += 1
        for span in record.gold_spans:
            if span.entity_type in DIRECT_IDENTIFIER_TYPES and span in exact:
                direct_counts[f"matched_{span.entity_type}"] += 1
        for span_range, gold_span in gold_by_range.items():
            predicted_span = predicted_by_range.get(span_range)
            if predicted_span is not None and predicted_span.entity_type != gold_span.entity_type:
                confusion[(gold_span.entity_type, predicted_span.entity_type)] += 1

    for entity_type in types:
        gold_count = sum(
            1
            for record in materialized
            for span in record.gold_spans
            if span.entity_type == entity_type
        )
        predicted_count = sum(
            1
            for record in materialized
            for span in record.predicted_spans
            if span.entity_type == entity_type
        )
        exact_counts[("gold", entity_type)] = gold_count
        exact_counts[("predicted", entity_type)] = predicted_count

    overall_gold = sum(len(record.gold_spans) for record in materialized)
    overall_predicted = sum(len(record.predicted_spans) for record in materialized)
    overall_matched = sum(value for (kind, _), value in exact_counts.items() if kind == "tp")
    metrics = _metric_dict(overall_matched, overall_predicted, overall_gold)
    by_entity = {
        entity_type: _metric_dict(
            exact_counts[("tp", entity_type)],
            exact_counts[("predicted", entity_type)],
            exact_counts[("gold", entity_type)],
        )
        for entity_type in sorted(types)
    }
    direct_gold = sum(
        direct_counts[f"gold_{entity_type}"] for entity_type in DIRECT_IDENTIFIER_TYPES
    )
    direct_matched = sum(
        direct_counts[f"matched_{entity_type}"] for entity_type in DIRECT_IDENTIFIER_TYPES
    )
    direct_recall = {
        entity_type: {
            "matched": direct_counts[f"matched_{entity_type}"],
            "gold": direct_counts[f"gold_{entity_type}"],
            "recall": _ratio(
                direct_counts[f"matched_{entity_type}"], direct_counts[f"gold_{entity_type}"]
            ),
        }
        for entity_type in DIRECT_IDENTIFIER_TYPES
    }
    direct_recall["overall"] = {
        "matched": direct_matched,
        "gold": direct_gold,
        "recall": _ratio(direct_matched, direct_gold),
    }

    return {
        "metadata": {
            "document_count": len(materialized),
            "gold_span_count": overall_gold,
            "predicted_span_count": overall_predicted,
            "matching": "exact entity_type and exact half-open range",
            "source_text_included": False,
        },
        "overall": metrics,
        "by_entity_type": by_entity,
        "direct_identifier_recall": direct_recall,
        "type_confusion_matrix": {
            gold_type: {
                predicted_type: confusion[(gold_type, predicted_type)]
                for predicted_type in sorted(types)
                if confusion[(gold_type, predicted_type)]
            }
            for gold_type in sorted(types)
            if any(confusion[(gold_type, predicted_type)] for predicted_type in types)
        },
        "missed_spans": sorted(
            missed,
            key=lambda item: (
                str(item["document_id"]),
                int(item["start"]),
                int(item["end"]),
                str(item["entity_type"]),
            ),
        ),
    }


def _metric_dict(matched: int, predicted: int, gold: int) -> dict[str, Any]:
    precision = _ratio(matched, predicted)
    recall = _ratio(matched, gold)
    return {
        "matched": matched,
        "predicted": predicted,
        "gold": gold,
        "precision": precision,
        "recall": recall,
        "f1": 0.0 if precision + recall == 0.0 else 2 * precision * recall / (precision + recall),
    }


def _ratio(numerator: int, denominator: int) -> float:
    return 0.0 if denominator == 0 else numerator / denominator
