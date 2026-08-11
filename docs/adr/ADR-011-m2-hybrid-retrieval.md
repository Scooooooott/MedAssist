# ADR-011: PostgreSQL Hybrid Retrieval with Reciprocal Rank Fusion

## Status

Accepted for implementation; quality and latency selection remain `NOT MEASURED`.

## Date

2026-08-07

## Context

Medical queries contain both semantic intent and exact strings such as drug names,
clinical abbreviations, and coding-system identifiers. M1 provides the vector
retrieval baseline. M2.1 adds a lexical channel and keeps vector-only and
lexical-only modes so that each change can be attributed in an ablation.

The lexical channel must index only the de-identified source-faithful `chunk.text`.
The contextual prefix introduced by M2.3 is an embedding-only input and must not
change lexical matches, generated-answer input, citation validation, or citation
display.

## Decision

Use PostgreSQL full-text search with generated `tsvector` columns and GIN indexes
as the first lexical implementation. Keep the configured `english` stemmed
column and the simple unstemmed column available for a controlled terminology
experiment. The selected lexical configuration is not considered optimal until
the experiment in [M2 hybrid retrieval](../experiments/M2-hybrid-retrieval.md)
has real dev-split data.

Expose additive `retrievalMode` values:

| Mode | Behavior |
|---|---|
| `VECTOR_ONLY` | Query embedding followed by vector search. |
| `LEXICAL_ONLY` | PostgreSQL full-text search on raw `chunk.text`. |
| `HYBRID` | Vector and lexical searches execute in parallel and are fused. |

Fuse independently ranked lists with weighted Reciprocal Rank Fusion:

```text
score(d) = sum(channel_weight / (rrf_k + rank))
```

The current code defaults are `HYBRID`, `rrf_k=60`, vector and lexical candidate
limits of 50, equal channel weights, a 500 ms overall retrieval deadline, and
cosine distance. These are implementation defaults, not measured optima.

Both channels receive the same metadata, status, version, model, contextual-mode,
and chunking-strategy filters at the database boundary. The two branches use
`CompletableFuture` with one shared deadline. A vector failure fails the request;
a lexical failure returns vector results with an explicit degradation reason.
The policy is explicit and observable, rather than inherited from future defaults.

## Boundaries and Safety

- Only de-identified `chunk.text` is eligible for lexical indexing.
- Request-controlled table names are forbidden; dimension and table routing use a
  fixed application allowlist.
- `WITHDRAWN` documents are excluded in every mode. Superseded documents remain
  opt-in only.
- Query traces and degradation reasons may be recorded, but raw query text and
  retrieved text must not be written to evaluation or audit artifacts.
- M4 must re-verify identity and trace-context propagation across both parallel
  branches after RBAC and context propagation are enabled.

## Alternatives Considered

| Alternative | Decision |
|---|---|
| BGE-M3 sparse output with `sparsevec` | Deferred experiment; it adds model-output and pgvector coupling before the lower-dependency baseline is measured. |
| BM25 in a separate search service | Rejected for M2.1 because it adds an operational dependency and makes local reproducibility harder. |
| Java 21 `StructuredTaskScope` | Rejected for the production baseline because it is preview API dependent; `CompletableFuture` is sufficient for the explicit deadline policy. |
| Serial vector then lexical execution | Rejected because it adds branch latencies and does not satisfy the required parallel execution experiment. |

## Reproducibility and Rollback

Every run must record the evaluation-set version, commit, embedding model name and
version, retrieval mode, lexical configuration, RRF parameters, filters, and random
seed. The migration and repository filters are additive. Rollback is performed by
selecting `VECTOR_ONLY` and disabling the lexical branch; the raw text and vector
index remain intact.

## Consequences

Hybrid retrieval improves the opportunity to recover exact medical terms, but no
quality, latency, or cost improvement is claimed here. The evidence is
`NOT MEASURED` until the licensed corpus and model assets are available and the
controlled experiment is run. The lexical index has additional storage and update
work, and parallel execution increases connection-pool pressure.

## Evidence

- [M2 hybrid retrieval experiment](../experiments/M2-hybrid-retrieval.md)
- [M2 retrieval engineering summary](../experiments/M2-retrieval-engineering.md)
