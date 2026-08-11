# ADR-012: Two-Tier Cross-Encoder Reranking

## Status

Accepted for implementation; model selection and performance are `NOT MEASURED`.

## Date

2026-08-07

## Context

Vector and lexical retrieval are inexpensive candidate-generation mechanisms but
are not ideal at ordering the final evidence set. M2.2 requires a cross-encoder
reranker, while the system must keep a practical online latency profile and a
larger offline quality ceiling for evaluation.

## Decision

Implement two explicit reranker profiles behind the model service `Rerank` API:

| Profile | Intended use | Current identity |
|---|---|---|
| `online` | Request path, lightweight MiniLM-class cross-encoder | Configured asset/version required; result `NOT MEASURED`. |
| `offline` | Evaluation upper-bound comparison, larger BGE reranker | Configured asset/version required; result `NOT MEASURED`. |

Retrieval flow is candidate generation, optional reranking, then final top-K. The
current code defaults are candidate `N=50`, final `K=5`, and reranking disabled.
The requirements' N values `{20, 50, 100}` and the online/offline profiles must
be evaluated before a production default is selected. No current default is
described as best.

Rerank requests contain the query and candidate IDs with source-faithful
`RetrievedChunk.text`. Context prefixes and generated answer text are excluded.
The client validates that the response contains exactly the known candidate IDs
with valid ranks and scores. Timeout or backend failure returns the original fused
candidate order with an explicit degradation reason; it never turns a reranker
failure into a retrieval failure.

## Safety and Resource Boundaries

- Production must use a real configured ONNX asset. Deterministic implementations
  are test-only and are not an operational fallback.
- Model name and version are returned and recorded with evaluation metadata.
- Candidate count is capped by configuration to bound CPU and memory work.
- Raw candidate text is not persisted in experiment metadata or logs.
- A reranker cannot introduce a chunk that was absent from retrieval results.

## Alternatives Considered

| Alternative | Rejection reason |
|---|---|
| Rerank every document directly | Violates the coarse-recall/precise-order design and is not bounded for online use. |
| Use only the large BGE reranker online | Quality may be attractive, but memory and latency risk are inappropriate before measurement. |
| Use only the lightweight model for all work | It removes the offline upper-bound comparison required to understand the engineering trade-off. |
| Fail the whole request on rerank failure | Reranking is an optimization layer; retrieval remains valid without it. |

## Reproducibility and Rollback

Record profile, model name/version, candidate N, final K, retrieval configuration,
hardware, batch size, timeout, evaluation-set version, commit, and random seed.
The feature can be rolled back by setting `rerankEnabled=false`; the fused
candidate path remains available.

## Consequences

The two-tier design makes the online/offline trade-off explicit and testable. It
adds model assets, process memory, and a failure/degradation path. Online P95,
memory, quality difference, and cost are all `NOT MEASURED` until approved model
bundles and a real corpus are provisioned.

## Evidence

- [M2 rerank experiment](../experiments/M2-rerank.md)
- [M2 retrieval engineering summary](../experiments/M2-retrieval-engineering.md)
