# De-identification Baseline v1

**Status: NOT MEASURED.** This committed document is a report template. No
de-identification metrics are claimed until an approved, local safe JSONL
annotation set is evaluated.

## Scope

The M1.3 evaluator measures exact span matches using `(entity_type, start, end)`.
It reports overall and per-entity precision, recall, and F1; direct-identifier
recall for `PERSON`, `MRN`, `SSN`, and `PHONE`; exact-range type confusions; and
missed spans containing only document IDs, entity types, and offsets. Source text
is neither required nor emitted.

## Reproduction Command

Run this from the repository root after creating the local, gitignored
annotation/prediction file:

```powershell
uv run --project tools/deid-eval medassist-deid-eval `
  --input data/eval/deid/predictions.jsonl `
  --output-json docs/experiments/deid-baseline-v1.json `
  --output-md docs/experiments/deid-baseline-v1.md
```

Each JSONL line must contain only `document_id`, `gold_spans`, and
`predicted_spans`, where every span contains only `entity_type`, `start`, and
`end`. The evaluator rejects source-text fields.

## Metrics

| Metric | Value |
|---|---:|
| Overall precision | NOT MEASURED |
| Overall recall | NOT MEASURED |
| Overall F1 | NOT MEASURED |
| Direct-identifier recall | NOT MEASURED |

Per-entity metrics, confusion counts, and missed-span details will be generated
by the command above after measurement. This template intentionally contains no
fabricated values.

## Improvement Directions

To be completed from actual missed-span patterns after the first approved run:

1. Review the highest-volume missed entity type and expand its recognizers or
   boundary rules.
2. Review exact-range type confusions and align entity-type normalization and
   recognizer precedence.
3. Prioritize missed direct identifiers, especially any `PERSON`, `MRN`, `SSN`,
   or `PHONE` spans, for targeted regression fixtures and threshold tuning.
