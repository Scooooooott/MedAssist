# M2.4 Embedding Model Experiment

## Status

`NOT MEASURED`. Multi-model configuration and dimension-isolated schema support
are present, but no approved three-model corpus run has been completed.

## Models and Controls

Compare at least:

1. M1 BGE-M3, dimension 1024.
2. One fixed medical-domain model, such as an approved MedCPT or BioLORD bundle.
3. One approved API embedding model with recorded price and version.

Hold evaluation records and source spans, chunking strategy/parameters, contextual
mode, retrieval mode, RRF/rerank configuration, filters, hardware, batch size,
seed, and request ordering constant. Change only embedding model identity and the
corresponding isolated index.

## Metadata and Command Template

For each model record name, immutable version, dimension, backend, asset digest or
provider model ID, tokenizer/prefix policy, batch size, commit, eval-set version,
seed, host, and whether the API price was actually observed.

```powershell
uv run --project tools/eval-harness eval-harness `
  --input <safe-results.jsonl> --output-json <model-run.json> `
  --output-markdown <model-run.md> --split dev
```

The model service must report readiness for each model and must not silently
replace a missing asset with a deterministic backend.

## Metrics and Acceptance Conditions

| Metric | Required result |
|---|---|
| recall@5, recall@10, MRR | `NOT MEASURED`, including all five categories |
| context_precision, faithfulness | `NOT MEASURED`, including all five categories |
| embedding latency and throughput | `NOT MEASURED` |
| resident memory | `NOT MEASURED` |
| API unit cost | `NOT MEASURED` until a price and token accounting are recorded |
| index isolation | Existing BGE-M3 rows must remain unchanged |

## Result Matrix

| Model | Dimension | recall@10 | MRR | context_precision | faithfulness | Latency | Memory | Cost | Status |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| BGE-M3 M1 control | 1024 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Medical-domain candidate | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| API candidate | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

## Selection Rule

Do not select a winner from this template. A later revision may select a model
only after all required metrics and category breakdowns are `MEASURED`, the M1
index is verified unchanged, and the ADR records the measured trade-off plus
conditions that trigger re-evaluation.

## Safety and Reproducibility Checklist

- [ ] No raw corpus, prompts, API keys, or provider responses are committed.
- [ ] All model identities are fixed; floating labels such as `latest` are rejected.
- [ ] Dimension-specific routing was used and cross-model vectors were not mixed.
- [ ] The reproducibility tuple and asset digests are present.
- [ ] Any missing model or provider is recorded as `BLOCKED BY EXTERNAL ASSET`.
