# ADR-014: Isolated Multi-Model Embedding Storage and Routing

## Status

Accepted for implementation; model selection is `NOT MEASURED`.

## Date

2026-08-07

## Context

M2.4 compares the M1 BGE-M3 baseline with a medical-domain model and an API model.
Public benchmark results do not establish performance on this project's medical
evaluation set. Different models may produce 768-, 1024-, or 1536-dimensional
vectors and must coexist without deleting or mixing the M1 index.

## Decision

Keep the 1024-dimensional baseline table and add separate dimension-specific
tables for 768 and 1536 dimensions. Route only through a fixed application
allowlist; table names are never assembled from request input. Every vector query
must bind model name, model version, dimension, and chunking strategy. The
database uniqueness boundary remains compatible with `(chunk_id, model_name,
model_version)` semantics, while dimension-specific tables keep pgvector HNSW
indexes valid.

The model service uses explicit model name/version declarations, supports multiple
registered models, and bounds residency through configuration. A deterministic
backend is test-only. Production requires fixed model assets and does not silently
fall back when an asset is missing.

The current retrieval default is `bge-m3` version `m1-baseline`, dimension 1024.
This is the compatibility baseline, not a measured M2 recommendation. Candidate
models, dimensions, latency, memory, and API price remain `NOT MEASURED`.

## Alternatives Considered

| Alternative | Rejection reason |
|---|---|
| Rebuild or clear the M1 index per model | Destroys the control and makes side-by-side comparison impossible. |
| One unconstrained variable-dimension vector column | Weakens HNSW index guarantees and makes model/dimension routing easier to mix accidentally. |
| Load every model permanently | Exceeds bounded memory assumptions for a small deployment. |
| Select floating identities such as `latest` | Breaks reproducibility and invalidates evaluation tuples. |

## Safety and Reproducibility

Each run records model name/version, dimension, backend, asset digest, tokenizer
identity, embedding input mode, context mode, chunking strategy, evaluation-set
version, commit, hardware, and random seed. Existing BGE-M3 rows are immutable
experiment controls. No raw texts or provider credentials belong in reports.

## Consequences

Separate tables cost storage and require migration/routing discipline, but they
make incompatible vector dimensions explicit and preserve the M1 control. Model
quality, resident memory, throughput, latency, and API cost are `NOT MEASURED`
until all three approved model bundles and the licensed corpus exist.

## Evidence

- [M2 embedding model experiment](../experiments/M2-embedding-models.md)
- [M2 retrieval engineering summary](../experiments/M2-retrieval-engineering.md)
