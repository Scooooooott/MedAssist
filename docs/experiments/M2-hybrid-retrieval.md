# M2.1 Hybrid Retrieval Experiment

## Status

`NOT MEASURED`. This document is an executable template. The licensed corpus,
approved model bundle, and a completed M1 baseline run are required before any
quality or latency conclusion.

## Question

Does PostgreSQL lexical retrieval plus weighted RRF recover exact medical terms
that vector-only retrieval misses, without violating the shared-filter, raw-text,
deadline, and degradation contracts?

## Controlled Variables

Hold constant evaluation-set version and source ranges, code commit, embedding
model/version/dimension, chunking strategy, contextual mode, top-K, metadata
filters, database hardware, connection-pool settings, random seed, and request
order. Change only retrieval mode, lexical configuration, RRF weights/k, or
parallel-vs-serial execution in separate runs.

Required modes: `VECTOR_ONLY`, `LEXICAL_ONLY`, `HYBRID`. Required lexical variants:
`english` stemmed and `simple` unstemmed. Candidate defaults currently in code:
50 per channel, `rrf_k=60`, equal weights, final top-K 5. These values are
`NOT MEASURED` as optimal.

## Inputs and Metadata

Record:

```text
eval_set_version=<for example golden-v2; do not invent a value>
code_commit=<full commit>
model_name=<fixed embedding identity>
model_version=<fixed embedding version>
dimension=<1024 or routed dimension>
chunking_strategy_id=<fixed strategy>
contextual_retrieval_mode=<OFF/RULE_BASED/LLM_GENERATED>
retrieval_mode=<VECTOR_ONLY/LEXICAL_ONLY/HYBRID>
lexical_configuration=<english|simple>
rrf_k=<integer>
vector_weight=<decimal>
lexical_weight=<decimal>
seed=<integer or NOT APPLICABLE>
hardware=<CPU/database deployment identity>
```

Use the repository's eval harness with source-range-based records. Store only
safe aggregate metadata and report URIs; do not put source text in the database.

Example command shape after assets are provisioned:

```powershell
uv run --project tools/eval-harness eval-harness `
  --input <safe-results.jsonl> --output-json <run.json> `
  --output-markdown <run.md> --split dev --quick
```

The exact CLI flags must match `tools/eval-harness --help` at execution time.

## Metrics and Acceptance

| Metric | Required result |
|---|---|
| recall@5, recall@10, MRR | `NOT MEASURED` until real dev run |
| Category-level retrieval metrics | `NOT MEASURED` for all five categories |
| Hybrid delta vs M1 vector baseline | `NOT MEASURED`; no improvement claimed |
| P95 retrieval latency excluding embedding | `NOT MEASURED`; target <200 ms is an acceptance target, not a result |
| Serial vs parallel latency | `NOT MEASURED`; parallel should approach the slower branch |
| Lexical failure degradation | Must return vector results and safe degradation metadata |
| Vector failure behavior | Must fail overall and never return lexical-only results |
| Context-only term lexical test | Must not match through context prefix |

## Result Table

| Configuration | recall@10 | MRR | P95 ms | Degradation behavior | Status |
|---|---:|---:|---:|---|---|
| M1 vector-only control | NOT MEASURED | NOT MEASURED | NOT MEASURED | N/A | NOT MEASURED |
| M2 lexical-only, english | NOT MEASURED | NOT MEASURED | NOT MEASURED | N/A | NOT MEASURED |
| M2 hybrid, english | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| M2 hybrid, simple | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

## Safety and Reproducibility Checklist

- [ ] Generated `tsvector` is based only on raw `chunk.text`.
- [ ] Both channels receive identical metadata/status/version filters.
- [ ] No raw query, source text, or PHI appears in artifacts.
- [ ] Parallel timing and injected failure tests are attached.
- [ ] Results include the full reproducibility tuple and configuration digest.
- [ ] Any non-result or blocked attempt is described without fabricated values.
