# Evaluation Harness

This tool validates licensed evaluation sets, computes source-range-based retrieval and
citation metrics, enforces rolling holdout discipline, and writes JSON and Markdown reports.
It never treats chunk IDs as ground truth and never fabricates unavailable RAGAS values.

## Retrieval Metrics

`precision_at_8` and `ndcg_at_8` use a fixed `K=8`. Precision divides the number of relevant
retrieved chunks in the first eight ranks by 8, including when fewer than eight chunks were
returned. Relevance is derived first by overlapping `supporting_spans` with the source character
ranges in `retrieved_chunks`; older inputs may provide `relevant_ranks` instead. When supplied,
the non-negative integer `relevant_count` sets the ideal relevant-item count for nDCG; otherwise
the number of relevant ranks is used as the fallback. Existing recall and MRR fields remain in
the report for compatibility.

## Dataset Validation

```powershell
../../.tools/uv.exe run medassist-eval-validate `
  --input ../../data/eval/golden/golden-v2.jsonl `
  --metadata ../../data/eval/metadata/golden-v2-splits.json `
  --expected-total 300
```

## Development Run

The input is JSONL containing per-record system results. Metadata is mandatory for a gate.

```powershell
../../.tools/uv.exe run medassist-eval `
  --input ../../target/eval/dev-results.jsonl `
  --output-json ../../target/eval/dev-report.json `
  --output-md ../../target/eval/dev-report.md `
  --split dev --quick `
  --eval-set-version golden-v2 --code-commit COMMIT `
  --model-name MODEL --model-version VERSION --random-seed 42 `
  --judge-model JUDGE
```

## Quality Thresholds

Thresholds must be derived from a real measured baseline. The generator refuses incomplete
metadata or unavailable faithfulness instead of creating placeholder quality claims.

```powershell
../../.tools/uv.exe run medassist-eval-thresholds `
  --baseline-json ../../target/eval/baseline.json `
  --output-json ../../config/evaluation/m2-thresholds.json
```

Pass the reviewed result to `medassist-eval --gate --threshold-config ...`. A failed gate
returns exit code 1 and prints the five worst source-text-free diagnostics. Input/configuration
errors return exit code 2.

Gate rule names `precision@8` and `nDCG@8` are aliases for the report fields
`precision_at_8` and `ndcg_at_8` (case-insensitive, alongside the existing recall aliases).

## Report Privacy

The JSON report keeps the `results` key for compatibility, but each entry is a safe summary only:
it contains the record ID, category, scalar counts, relevant ranks, and per-record numeric
metrics. It never includes question, answer, prompt, candidate text, retrieved chunk text, or
supporting evidence text. Metric computation runs against the original in-memory records and the
safe summaries are built separately without mutating those records.

## Holdout

Holdout execution requires `--confirm-holdout`, the exact `--holdout-version`, and committed
metadata. Add `--mark-holdout-consumed` only for an archived milestone run. A failed gate can
never consume a holdout subset.
