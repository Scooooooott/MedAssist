from __future__ import annotations

import math
from dataclasses import asdict, dataclass
from typing import Any

RETRIEVAL_K = 8


@dataclass(frozen=True)
class Metrics:
    citation_validity: float
    abstain_when_expected: float
    false_abstain_rate: float
    unauthorized_violations: int
    recall_at_k: float
    mrr: float
    recall_at_5: float = 0.0
    recall_at_10: float = 0.0
    precision_at_8: float = 0.0
    ndcg_at_8: float = 0.0


RAGAS_NAMES = (
    "faithfulness",
    "answer_relevancy",
    "context_precision",
    "context_recall",
)


def compute_metrics(records: list[dict[str, Any]]) -> Metrics:
    if not records:
        return Metrics(0.0, 0.0, 0.0, 0, 0.0, 0.0)
    citation_total = 0
    citation_valid = 0
    expected_abstain_total = 0
    expected_abstain_correct = 0
    answerable_total = 0
    false_abstain = 0
    unauthorized_violations = 0
    recall_hits = {5: 0, 10: 0}
    precision_at_8_total = 0.0
    ndcg_at_8_total = 0.0
    reciprocal_ranks: list[float] = []
    for record in records:
        citations = _list_of_mappings(record.get("citations", []))
        citation_total += len(citations)
        citation_valid += sum(1 for citation in citations if citation.get("valid") is True)
        expected_behavior = record.get("expected_behavior", "")
        abstained = bool(record.get("abstained", False))
        if expected_behavior == "abstain":
            expected_abstain_total += 1
            expected_abstain_correct += int(abstained)
        else:
            answerable_total += 1
            false_abstain += int(abstained)
        unauthorized_violations += _nonnegative_int(record.get("unauthorized_violations", 0))
        retrieval = retrieval_metrics_for_record(record)
        relevant_ranks = retrieval["relevant_ranks"]
        for cutoff in recall_hits:
            recall_hits[cutoff] += int(any(rank <= cutoff for rank in relevant_ranks))
        reciprocal_ranks.append(1.0 / min(relevant_ranks) if relevant_ranks else 0.0)
        precision_at_8_total += retrieval["precision_at_8"]
        ndcg_at_8_total += retrieval["ndcg_at_8"]
    recall_at_5 = safe_ratio(recall_hits[5], len(records))
    recall_at_10 = safe_ratio(recall_hits[10], len(records))
    return Metrics(
        citation_validity=safe_ratio(citation_valid, citation_total),
        abstain_when_expected=safe_ratio(expected_abstain_correct, expected_abstain_total),
        false_abstain_rate=safe_ratio(false_abstain, answerable_total),
        unauthorized_violations=unauthorized_violations,
        recall_at_k=recall_at_10,
        mrr=sum(reciprocal_ranks) / len(reciprocal_ranks),
        recall_at_5=recall_at_5,
        recall_at_10=recall_at_10,
        precision_at_8=precision_at_8_total / len(records),
        ndcg_at_8=ndcg_at_8_total / len(records),
    )


def relevant_ranks_for_record(record: dict[str, Any]) -> list[int]:
    supporting_spans = _list_of_mappings(record.get("supporting_spans", []))
    retrieved_chunks = _list_of_mappings(record.get("retrieved_chunks", []))
    if "supporting_spans" in record and "retrieved_chunks" in record:
        ranks: list[int] = []
        for rank, chunk in enumerate(retrieved_chunks, start=1):
            if any(_ranges_overlap(span, chunk) for span in supporting_spans):
                ranks.append(rank)
        return ranks
    legacy_ranks = record.get("relevant_ranks", [])
    if not isinstance(legacy_ranks, list):
        return []
    return sorted(
        {
            rank
            for value in legacy_ranks
            if _is_positive_int(value)
            for rank in [int(value)]
        }
    )


def retrieval_metrics_for_record(record: dict[str, Any]) -> dict[str, Any]:
    relevant_ranks = relevant_ranks_for_record(record)
    relevant_count = _relevant_count(record, relevant_ranks)
    relevant_at_8 = sum(rank <= RETRIEVAL_K for rank in relevant_ranks)
    return {
        "relevant_ranks": relevant_ranks,
        "relevant_count": relevant_count,
        "relevant_at_8": relevant_at_8,
        "precision_at_8": relevant_at_8 / RETRIEVAL_K,
        "ndcg_at_8": _ndcg_at_k(relevant_ranks, relevant_count, RETRIEVAL_K),
    }


def safe_record_summary(record: dict[str, Any], index: int) -> dict[str, Any]:
    """Return an auditable scalar/ranking summary without copying source text."""

    citations = _list_of_mappings(record.get("citations", []))
    valid_citations = sum(1 for citation in citations if citation.get("valid") is True)
    supporting_spans = _list_of_mappings(record.get("supporting_spans", []))
    retrieved_chunks = _list_of_mappings(record.get("retrieved_chunks", []))
    retrieval = retrieval_metrics_for_record(record)
    ranks = retrieval["relevant_ranks"]
    expected_behavior = record.get("expected_behavior")
    safe_expected_behavior = (
        expected_behavior
        if isinstance(expected_behavior, str) and expected_behavior in {"answer", "abstain"}
        else "unknown"
    )
    retrieval_metrics = {
        "precision_at_8": retrieval["precision_at_8"],
        "ndcg_at_8": retrieval["ndcg_at_8"],
        "recall_at_5": float(any(rank <= 5 for rank in ranks)),
        "recall_at_10": float(any(rank <= 10 for rank in ranks)),
        "recall_at_k": float(any(rank <= 10 for rank in ranks)),
        "mrr": 1.0 / min(ranks) if ranks else 0.0,
        "citation_validity": safe_ratio(valid_citations, len(citations)),
        "abstain_when_expected": float(
            expected_behavior == "abstain" and bool(record.get("abstained", False))
        ),
        "false_abstain_rate": float(
            expected_behavior != "abstain" and bool(record.get("abstained", False))
        ),
        "unauthorized_violations": _nonnegative_int(record.get("unauthorized_violations", 0)),
    }
    return {
        "id": record.get("id", index),
        "category": str(record.get("category", "uncategorized")),
        "expected_behavior": safe_expected_behavior,
        "abstained": bool(record.get("abstained", False)),
        "citation_count": len(citations),
        "valid_citation_count": valid_citations,
        "retrieved_chunk_count": len(retrieved_chunks),
        "supporting_span_count": len(supporting_spans),
        "relevant_count": retrieval["relevant_count"],
        "relevant_at_8": retrieval["relevant_at_8"],
        "relevant_ranks": ranks,
        "unauthorized_violations": retrieval_metrics["unauthorized_violations"],
        "metrics": retrieval_metrics,
    }


def category_metrics(records: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for record in records:
        grouped.setdefault(str(record.get("category", "uncategorized")), []).append(record)
    return {
        category: {"count": len(items), "metrics": asdict(compute_metrics(items))}
        for category, items in sorted(grouped.items())
    }


def worst_cases(records: list[dict[str, Any]], limit: int = 10) -> list[dict[str, object]]:
    diagnostics: list[dict[str, object]] = []
    for index, record in enumerate(records):
        failures: list[str] = []
        citations = _list_of_mappings(record.get("citations", []))
        if any(citation.get("valid") is not True for citation in citations):
            failures.append("citation_validity")
        expected_behavior = record.get("expected_behavior", "")
        abstained = bool(record.get("abstained", False))
        if expected_behavior == "abstain" and not abstained:
            failures.append("abstain_when_expected")
        if expected_behavior != "abstain" and abstained:
            failures.append("false_abstain_rate")
        if _nonnegative_int(record.get("unauthorized_violations", 0)) > 0:
            failures.append("unauthorized_violations")
        ranks = relevant_ranks_for_record(record)
        if not any(rank <= 5 for rank in ranks):
            failures.append("recall_at_5")
        if not any(rank <= 10 for rank in ranks):
            failures.append("recall_at_10")
        if failures:
            diagnostics.append(
                {
                    "id": str(record.get("id", index)),
                    "category": str(record.get("category", "uncategorized")),
                    "metric_failures": sorted(set(failures)),
                }
            )
    diagnostics.sort(key=_diagnostic_sort_key)
    return diagnostics[:limit]


def _diagnostic_sort_key(item: dict[str, object]) -> tuple[int, str]:
    failures = item.get("metric_failures")
    failure_count = len(failures) if isinstance(failures, list) else 0
    return -failure_count, str(item.get("id", ""))


def ragas_metrics(records: list[dict[str, Any]]) -> dict[str, dict[str, object]]:
    result: dict[str, dict[str, object]] = {}
    for name in RAGAS_NAMES:
        supplied = [float(record[name]) for record in records if _is_number(record.get(name))]
        result[name] = {
            "status": "provided" if supplied else "unavailable",
            "value": sum(float(value) for value in supplied) / len(supplied) if supplied else None,
            "source": "input" if supplied else None,
        }
    return result


def safe_ratio(numerator: float, denominator: float) -> float:
    return 0.0 if denominator == 0 else numerator / denominator


def _relevant_count(record: dict[str, Any], relevant_ranks: list[int]) -> int:
    explicit_count = _nonnegative_int_value(record.get("relevant_count"))
    return explicit_count if explicit_count is not None else len(relevant_ranks)


def _ndcg_at_k(relevant_ranks: list[int], relevant_count: int, k: int) -> float:
    dcg = sum(1.0 / _log2(rank + 1) for rank in relevant_ranks if rank <= k)
    ideal_count = min(relevant_count, k)
    if ideal_count <= 0:
        return 0.0
    ideal_dcg = sum(1.0 / _log2(rank + 1) for rank in range(1, ideal_count + 1))
    return min(1.0, safe_ratio(dcg, ideal_dcg))


def _log2(value: int) -> float:
    return math.log2(value)


def _ranges_overlap(span: dict[str, Any], chunk: dict[str, Any]) -> bool:
    span_doc = span.get("document_version_id")
    chunk_doc = chunk.get("document_version_id")
    if span_doc != chunk_doc:
        return False
    span_range = _range_from_mapping(span, "char_start", "char_end")
    chunk_range = _range_from_chunk(chunk)
    if span_range is None or chunk_range is None:
        return False
    return max(span_range[0], chunk_range[0]) < min(span_range[1], chunk_range[1])


def _range_from_chunk(chunk: dict[str, Any]) -> tuple[int, int] | None:
    for start_key, end_key in (
        ("source_char_start", "source_char_end"),
        ("char_start", "char_end"),
    ):
        result = _range_from_mapping(chunk, start_key, end_key)
        if result is not None:
            return result
    source_range = chunk.get("source_char_range")
    if isinstance(source_range, dict):
        return _range_from_mapping(source_range, "char_start", "char_end")
    if (
        isinstance(source_range, list)
        and len(source_range) == 2
        and all(_is_nonnegative_int(v) for v in source_range)
    ):
        start = _nonnegative_int_value(source_range[0])
        end = _nonnegative_int_value(source_range[1])
        if start is None or end is None:
            return None
        return (start, end) if end > start else None
    return None


def _range_from_mapping(
    mapping: dict[str, Any], start_key: str, end_key: str
) -> tuple[int, int] | None:
    start = mapping.get(start_key)
    end = mapping.get(end_key)
    start_value = _nonnegative_int_value(start)
    end_value = _nonnegative_int_value(end)
    if start_value is None or end_value is None or end_value <= start_value:
        return None
    return start_value, end_value


def _list_of_mappings(value: object) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def _is_number(value: object) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def _is_positive_int(value: object) -> bool:
    integer = _nonnegative_int_value(value)
    return integer is not None and integer > 0


def _is_nonnegative_int(value: object) -> bool:
    return _nonnegative_int_value(value) is not None


def _nonnegative_int(value: object) -> int:
    integer = _nonnegative_int_value(value)
    return integer if integer is not None else 0


def _nonnegative_int_value(value: object) -> int | None:
    if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
        return value
    if isinstance(value, float) and value.is_integer() and value >= 0:
        return int(value)
    return None
