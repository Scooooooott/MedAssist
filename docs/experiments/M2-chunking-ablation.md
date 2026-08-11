# M2.5 Chunking Strategy Ablation

## Status

`NOT MEASURED`. The three strategy implementations and strategy-scoped metadata
are available; no complete 27-combination run or 20-item human review has been
completed.

## Matrix and Controls

Strategies: `fixed-v1`, `structure-v1`, and `semantic-v1`.

Scan `targetTokens in {256, 512, 1024}` and `overlapTokens in {0, 50, 128}`:
27 combinations total. Invalid configurations must fail validation and be listed
as invalid, never silently repaired.

Hold constant source corpus and eval-set version, embedding model/version/dimension,
context mode, retrieval mode, RRF settings, reranker state, filters, tokenizer or
token-counter identity, hardware, and seed. The only changed variable is the
chunking strategy/parameter combination.

## Metadata and Command Template

Record strategy ID, target/overlap, chunk count, token counter, source-range index
version, model tuple, commit, eval-set version, seed, and database schema/migration
version. Use source-character spans to derive relevant chunks dynamically.

```powershell
uv run --project tools/eval-harness eval-harness `
  --input <safe-results.jsonl> --output-json <chunking-run.json> `
  --output-markdown <chunking-run.md> --split dev
```

## Metrics and Acceptance Conditions

| Metric | Required result |
|---|---|
| recall@5, recall@10, MRR | `NOT MEASURED` per combination and category |
| context_precision, faithfulness | `NOT MEASURED` |
| chunk count and length distribution | `NOT MEASURED` |
| mean distinct documents retrieved | `NOT MEASURED` |
| citation readability review | `NOT MEASURED`; at least 20 reviewed items required |
| isolation | Strategy ID must be present in storage and retrieval filters |

## Result Table

| Strategy | target | overlap | recall@10 | MRR | Chunk stats | Readability | Status |
|---|---:|---:|---:|---:|---|---|---|
| fixed-v1 | 256/512/1024 | 0/50/128 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| structure-v1 | 256/512/1024 | 0/50/128 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| semantic-v1 | 256/512/1024 | 0/50/128 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

## Selection Rule

Keep `structure-v1` as the current code default until one complete controlled
matrix identifies a winner. Any default change must update application configuration,
this report, and [ADR-015](../adr/ADR-015-m2-chunking-strategies.md) with measured
evidence and a rollback note.

## Safety and Reproducibility Checklist

- [ ] Evaluation truth is source-character based, never chunk-ID based.
- [ ] All 27 combinations are accounted for as measured or explicitly invalid.
- [ ] No source text or PHI appears in the report or review artifact.
- [ ] Human readability comments are safe, aggregate, and traceable to item IDs.
- [ ] The model, corpus, code, configuration, and seed tuple is complete.
