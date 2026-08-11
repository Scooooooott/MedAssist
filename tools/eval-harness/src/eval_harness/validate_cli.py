from __future__ import annotations

import argparse
import json
from pathlib import Path

from .dataset import load_jsonl, validate_dataset
from .holdout import load_holdout_metadata


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate a MedAssist evaluation dataset and holdout metadata."
    )
    parser.add_argument("--input", required=True, type=Path, help="Evaluation dataset JSONL file.")
    parser.add_argument("--metadata", type=Path, help="Committed holdout metadata JSON file.")
    parser.add_argument(
        "--expected-total", type=int, help="Require exactly this many records, e.g. 200 or 300."
    )
    parser.add_argument(
        "--output-json", type=Path, help="Optional machine-readable validation result."
    )
    args = parser.parse_args()
    try:
        records = load_jsonl(args.input)
        metadata = load_holdout_metadata(args.metadata) if args.metadata else None
        result = validate_dataset(records, expected_total=args.expected_total, metadata=metadata)
    except ValueError as exc:
        print(
            json.dumps({"valid": False, "error_count": 1, "errors": [str(exc)]}, ensure_ascii=True)
        )
        return 2
    document = result.as_dict()
    print(json.dumps(document, indent=2, ensure_ascii=True))
    if args.output_json:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(
            json.dumps(document, indent=2, ensure_ascii=True) + "\n", encoding="utf-8"
        )
    return 0 if result.valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
