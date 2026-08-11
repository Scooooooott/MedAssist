# ADR-015: Strategy-Scoped Chunking Ablation

## Status

Accepted for implementation; the winning strategy and parameters are `NOT MEASURED`.

## Date

2026-08-07

## Context

M1 supplies structure-aware chunking. M2.5 compares it with a fixed-length
baseline and sentence-embedding semantic chunking. Chunk IDs are not stable
ground truth because strategy changes alter the chunk set. Evaluation truth must
remain anchored to source-document character ranges.

## Decision

Use the existing pluggable `Chunker` boundary and assign each output a stable
`chunking_strategy_id`. The current strategies are `fixed-v1`, `structure-v1`, and
`semantic-v1`. Store strategy identity in uniqueness and retrieval filters so
artifacts can coexist and cannot silently contaminate the default index.

The scan matrix is `targetTokens in {256, 512, 1024}` crossed with
`overlapTokens in {0, 50, 128}` for each strategy where the configuration is
valid. Hold constant evaluation set, embedding model/version, retrieval mode,
RRF settings, filters, reranking state, and answer-generation settings. Compare
recall@5, recall@10, MRR, context metrics, chunk count and length distribution,
documents per query, and at least 20 human readability assessments.

The current production default remains `structure-v1`; current application
defaults are not an experimental winner. Parameters and a replacement default
may be selected only after a complete controlled run and ADR/report update.

## Alternatives Considered

| Alternative | Rejection reason |
|---|---|
| Separate database schema for every combination | Strong isolation but excessive operational overhead for the first ablation. |
| Store only chunk IDs in the evaluation set | Invalidates the evaluation when chunking changes; source ranges are required. |
| Tune chunking and embeddings together | Prevents attribution of an observed change. |
| Make semantic chunking the default immediately | No project-specific evidence exists yet. |

## Safety and Reproducibility

- Chunking runs only on de-identified parsed content.
- Source ranges are preserved and no report includes source text.
- Every artifact records strategy ID, target/overlap settings, tokenizer or token
  counter identity, model tuple, corpus version, commit, and seed.
- Invalid combinations fail validation rather than being silently normalized.

## Consequences

Strategy-scoped storage allows fair comparisons and rollback but increases rows,
indexing work, and cleanup requirements. The optimal parameters, quality,
latency, storage, and human-readability results are `NOT MEASURED`.

## Evidence

- [M2 chunking ablation](../experiments/M2-chunking-ablation.md)
- [M2 retrieval engineering summary](../experiments/M2-retrieval-engineering.md)
