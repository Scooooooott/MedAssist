# M2 Retrieval Engineering Summary

## Status

`NOT MEASURED` for all quality, latency, memory, and monetary results. This
summary records the current implementation decisions and the evidence still
required for M2.9. It must be updated with real artifacts before a milestone claim.

## M1 to M2 Metric Evolution

| Change | precision@8 | nDCG@8 | recall@10 | context_precision | faithfulness | P50/P95/P99 latency | Memory | Cost | Status/notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| M1 vector-only baseline | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | M1 control run pending |
| + M2.1 hybrid retrieval | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | See hybrid experiment |
| + M2.2 rerank | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | See rerank experiment |
| + M2.3 contextual retrieval | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | Cost gate and six-boundary checks pending |
| M2.4 embedding model change | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | Three-model run pending |
| M2.5 chunking parameter change | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | 27-combination ablation pending |
| M2.6 version governance | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | Functional behavior is test-covered; quality effect pending |
| M2.7 cache | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | Cache defaults remain disabled |
| M2.8 CI gate | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | Gate mechanism unit tests only; real 30-record PR run pending |

## Current Defaults and Selection State

| Area | Current code default | Selection state |
|---|---|---|
| Retrieval mode | `HYBRID` | Base application default; M1 control uses the explicit `m1-baseline` profile with `VECTOR_ONLY`; not measured optimal |
| RRF | `k=60`, equal weights, 50 candidates/channel | Code default only |
| Rerank | Disabled; candidate N=50; final K=8 | Code default/control; not a quality selection |
| Context | `OFF` | Safe baseline; LLM mode requires approved cost artifact |
| Embedding | `bge-m3`, `m1-baseline`, 1024 dimensions | M1 compatibility control |
| Chunking | `structure-v1` | M1 compatibility control; ablation winner not known |
| Cache | Embedding and complete-answer caches disabled | Deliberate pre-M3 PHI-control boundary |
| Staleness | Three years from effective date | Requirement-aligned default; threshold sensitivity not measured |

No row above claims an optimum. Configuration changes require a measured report,
ADR update, and rollback instruction.

For reproducible comparisons, run the M1 control with the `m1-baseline` Spring
profile and record `retrieval_mode=VECTOR_ONLY` in the run metadata. M2 hybrid
runs use the base application default `HYBRID` unless an experiment explicitly
selects another mode.

## Implementation and Evidence Map

| Capability | Implementation state | Evidence required |
|---|---|---|
| PostgreSQL FTS, RRF, modes, parallel deadline | Implemented/tested at unit level | Licensed corpus recall/latency and failure run |
| Online/offline rerank client and model service contract | Implemented/tested at unit/contract level | Real model P95, memory, quality comparison |
| Rule/LLM contextual modes and cache key | Implemented/tested at unit level | Approved ten-chunk cost estimate and corpus ablation |
| Multi-model registry and dimension routing | Implemented/tested at unit/config level | Three approved model bundles and isolated full run |
| Fixed/structure/semantic chunkers | Implemented/tested at unit level | 27-run ablation and 20 human reviews |
| Evaluation harness and threshold gate | Implemented/tested with safe fixtures; JSON per-record output is text-free | Real 300-record dataset and deliberate regression run |

## Blocked or Not-Yet-Validated Attempt

The required three-consecutive-run MinIO-to-answer acceptance path has not been
completed. It is `BLOCKED BY EXTERNAL ASSET`: the licensed corpus, production
model bundles, configured providers, and dependent Docker services are not
available in the current workspace. This is a recorded external prerequisite,
not a fabricated benchmark failure. No quality or latency number is inferred from
unit tests.

The LLM contextual path is similarly gated by an approved cost artifact. The
current cost report remains `NOT MEASURED`, so the implementation correctly does
not authorize a full LLM backfill.

## M2 Acceptance Checklist

- [ ] M1 vector-only dev baseline is measured and archived.
- [ ] Hybrid experiment measures all three modes, lexical stemmer variants,
  parallel timing, and both failure policies.
- [ ] Rerank experiment measures N=20/50/100, both profiles, P50/P95/P99, incremental
  RSS, and degradation.
- [ ] Context experiment records ten-chunk actual tokens and cost before LLM use,
  then compares all three modes and six text boundaries.
- [ ] Embedding experiment runs three models with category breakdowns and preserves
  the BGE-M3 control index.
- [ ] Chunking experiment accounts for all 27 combinations and 20 readability
  reviews with source-span truth.
- [ ] 300-record evaluation and rolling holdout metadata are validated.
- [ ] PR, nightly, and milestone workflows are run with safe failure diagnostics.
- [ ] `holdout-v2` is run once after tuning and marked consumed only then.
- [ ] Every measured row has a reproducibility tuple and no raw source content.

## Evidence Links

- [M2 hybrid retrieval](M2-hybrid-retrieval.md)
- [M2 rerank](M2-rerank.md)
- [M2 embedding models](M2-embedding-models.md)
- [M2 chunking ablation](M2-chunking-ablation.md)
- [ADR-011](../adr/ADR-011-m2-hybrid-retrieval.md)
- [ADR-012](../adr/ADR-012-m2-rerank-tiers.md)
- [ADR-013](../adr/ADR-013-m2-contextual-retrieval.md)
- [ADR-014](../adr/ADR-014-m2-embedding-models.md)
- [ADR-015](../adr/ADR-015-m2-chunking-strategies.md)
