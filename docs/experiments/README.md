# Retrieval Experiments

This index is the M2 experiment entry point. Dates identify document preparation;
they are not run dates. A result is considered measured only when the report
contains the input artifact identity, code commit, model tuple, command, and
persisted output from an approved corpus run.

## Status

| Experiment | Scope | Status | ADR |
|---|---|---|---|
| M1 de-identification baseline | Span-level safe aggregate metrics | Baseline artifact exists; corpus result `NOT MEASURED` | Existing M1 baseline documentation |
| Contextual retrieval cost | Ten-record budget-gate procedure | Procedure implemented; approved measurement `NOT MEASURED` | [ADR-013](../adr/ADR-013-m2-contextual-retrieval.md) |
| M2 hybrid retrieval | Vector, lexical, and RRF modes | Template ready; result `NOT MEASURED` | [ADR-011](../adr/ADR-011-m2-hybrid-retrieval.md) |
| M2 rerank tiers | Online/offline cross-encoder comparison | Template ready; result `NOT MEASURED` | [ADR-012](../adr/ADR-012-m2-rerank-tiers.md) |
| M2 embedding models | BGE-M3, medical, and API comparison | Template ready; approved assets/result `NOT MEASURED` | [ADR-014](../adr/ADR-014-m2-embedding-models.md) |
| M2 chunking ablation | Three strategies and 27 parameter combinations | Template ready; result `NOT MEASURED` | [ADR-015](../adr/ADR-015-m2-chunking-strategies.md) |
| M2 summary | M1 to M2 metric evolution and selection | Awaiting controlled experiment outputs | ADRs 011-015 |

## Execution Discipline

1. Run one controlled experiment at a time. Change one variable only.
2. Use the same licensed, de-identified evaluation corpus for comparisons.
3. Record `(eval_set_version, code_commit, model_name, model_version)` plus
   configuration digest, hardware, seed, and command.
4. Use source-character supporting spans and derive relevant chunks at evaluation
   time. Never bind ground truth to chunk IDs.
5. M1 closeout must run `holdout-v1` once and then mark it consumed. It remains
   available while that external run is pending. M2 must reserve and use
   `holdout-v2` only at the M2 milestone; reserve `holdout-v3` for M3. A holdout
   run requires explicit confirmation and must be marked consumed only after a
   real archived run.
6. Do not commit raw source text, prompts, answers, PHI, local paths containing
   secrets, or unredacted logs. Reports contain aggregate or safe diagnostics only.

## Required Result States

Use exactly one of these labels for each result: `MEASURED`, `NOT MEASURED`,
`BLOCKED BY EXTERNAL ASSET`, or `INVALID RUN`. Never replace an unavailable
measurement with zero, an estimate, or a claimed improvement.

## Related Documents

- [M2 hybrid retrieval](M2-hybrid-retrieval.md)
- [M2 rerank](M2-rerank.md)
- [M2 embedding models](M2-embedding-models.md)
- [M2 chunking ablation](M2-chunking-ablation.md)
- [M2 retrieval engineering summary](M2-retrieval-engineering.md)
- [Contextual retrieval cost report](M2-contextual-retrieval-cost.md)
