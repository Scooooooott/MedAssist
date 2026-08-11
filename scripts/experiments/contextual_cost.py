from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class CostEstimate:
    chunk_count: int
    sample_count: int
    average_input_tokens: float
    average_output_tokens: float
    input_cost_per_million: float
    output_cost_per_million: float
    estimated_total_usd: float
    budget_usd: float
    within_budget: bool


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Estimate contextual retrieval generation cost."
    )
    parser.add_argument("--sample", type=Path, required=True)
    parser.add_argument("--chunk-count", type=int, required=True)
    parser.add_argument("--input-cost-per-million", type=float, required=True)
    parser.add_argument("--output-cost-per-million", type=float, required=True)
    parser.add_argument("--budget-usd", type=float, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.chunk_count < 1 or args.budget_usd < 0:
        raise SystemExit("chunk count must be positive and budget must be non-negative")
    records = load_sample(args.sample)
    estimate = estimate_cost(
        records,
        chunk_count=args.chunk_count,
        input_cost_per_million=args.input_cost_per_million,
        output_cost_per_million=args.output_cost_per_million,
        budget_usd=args.budget_usd,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(asdict(estimate), indent=2) + "\n", encoding="utf-8"
    )
    return 0 if estimate.within_budget else 1


def load_sample(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list) or len(payload) != 10:
        raise SystemExit("sample must contain exactly 10 token-usage records")
    records: list[dict[str, Any]] = []
    for item in payload:
        if not isinstance(item, dict):
            raise SystemExit("each sample item must be an object")
        if set(item) != {"input_tokens", "output_tokens"}:
            raise SystemExit(
                "sample items may contain only input_tokens and output_tokens"
            )
        records.append(item)
    return records


def estimate_cost(
    records: list[dict[str, Any]],
    *,
    chunk_count: int,
    input_cost_per_million: float,
    output_cost_per_million: float,
    budget_usd: float,
) -> CostEstimate:
    average_input = sum(int(item["input_tokens"]) for item in records) / len(records)
    average_output = sum(int(item["output_tokens"]) for item in records) / len(records)
    total = chunk_count * (
        average_input * input_cost_per_million / 1_000_000
        + average_output * output_cost_per_million / 1_000_000
    )
    return CostEstimate(
        chunk_count=chunk_count,
        sample_count=len(records),
        average_input_tokens=average_input,
        average_output_tokens=average_output,
        input_cost_per_million=input_cost_per_million,
        output_cost_per_million=output_cost_per_million,
        estimated_total_usd=round(total, 6),
        budget_usd=budget_usd,
        within_budget=total <= budget_usd,
    )


if __name__ == "__main__":
    raise SystemExit(main())
