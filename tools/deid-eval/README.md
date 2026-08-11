# MedAssist De-identification Evaluation

This is an independent Python 3.12 `uv` project for M1.3 span-level evaluation.
It accepts safe JSONL annotations and never needs or emits source text.

## Input contract

Each non-empty JSONL line must contain exactly these fields:

```json
{"document_id":"doc-001","gold_spans":[{"entity_type":"PERSON","start":10,"end":15}],"predicted_spans":[{"entity_type":"PERSON","start":10,"end":15}]}
```

`start` is inclusive and `end` is exclusive. Span offsets must be non-negative,
with `end > start`. Entity types are trimmed and upper-cased. Duplicate spans
within one document are rejected. Fields such as `text`, `source_text`, and
`content` are not accepted, so source text cannot accidentally enter the
evaluation contract.

## Run

```powershell
uv run --project tools/deid-eval medassist-deid-eval `
  --input data/eval/deid/predictions.jsonl `
  --output-json docs/experiments/deid-baseline-v1.json `
  --output-md docs/experiments/deid-baseline-v1.md
```

Both outputs are deterministic for the same input bytes. The JSON report
contains exact-span overall and per-entity metrics, direct-identifier recall
for `PERSON`, `MRN`, `SSN`, and `PHONE`, an exact-range type-confusion matrix,
and missed spans containing only `document_id`, `entity_type`, `start`, and
`end`.

Run the project checks with:

```powershell
uv run --project tools/deid-eval --group dev pytest tools/deid-eval/tests
uv run --project tools/deid-eval --group dev ruff check tools/deid-eval
uv run --project tools/deid-eval --group dev mypy tools/deid-eval/src
```

The committed Markdown baseline is a template and is explicitly marked
`NOT MEASURED` until an approved annotation set is evaluated. Do not commit
the local evaluation JSONL or any source text.
