# ADR-013: Contextual Retrieval as an Embedding-Only Prefix

## Status

Accepted for implementation; cost and quality comparison are `NOT MEASURED`.

## Date

2026-08-07

## Context

A chunk can be semantically ambiguous when detached from its document title,
publisher, and section hierarchy. M2.3 evaluates a zero-cost rule prefix against
an LLM-generated 50-100 word context. The implementation must preserve the source
text and must not make generated context part of citation evidence.

## Decision

Store context separately from `chunk.text`. Support `OFF`, `RULE_BASED`, and
`LLM_GENERATED` modes. The current implementation uses the following boundaries:

| Path | Allowed input |
|---|---|
| Embedding | `context_prefix + source-faithful chunk.text` |
| Lexical retrieval | Raw `chunk.text` only |
| Answer generation | Raw `chunk.text` and explicit source fields only |
| Citation validation/display | Raw `chunk.text` only |

Rule context consists of document title, publisher, breadcrumb, and a shared
document summary. LLM context uses a narrow client with separate shared-document
and per-chunk prompts. Results are cached by document version, strategy, chunk
ordinal, mode, and prompt version. LLM failures fall back to rule context and are
reported as a degradation rather than blocking the batch.

LLM mode is fail-closed on cost approval: it requires a reviewed JSON estimate,
the configured SHA-256 to match, and `within_budget=true`. The repository's
current cost report remains `NOT MEASURED`; no full-corpus LLM generation is
authorized by this ADR until the required ten-chunk measurement is recorded.

## Alternatives Considered

| Alternative | Rejection reason |
|---|---|
| Overwrite `chunk.text` with context | Breaks source offsets, lexical purity, citations, and display. |
| Put context into the lexical `tsvector` | Allows generated words to create lexical matches and violates M2.1. |
| Generate context at query time | Adds per-request cost and nondeterminism; it also makes citation boundaries unclear. |
| Require LLM context for successful ingestion | A provider outage would block safe source-faithful ingestion; rule fallback is bounded and auditable. |

## Safety and Reproducibility

- Context generation runs only after parsing and de-identification.
- Context is never treated as evidence and never becomes a quoted span.
- Cache keys include prompt version and strategy so prompt changes cannot reuse
  stale outputs silently.
- The estimate artifact, its SHA-256, provider/model identity, token counts, and
  sampling command must be recorded for any LLM run.
- Reports must contain no raw source text, prompts, or PHI.

## Consequences

Rule-based context has predictable zero provider cost but may provide less
semantic disambiguation. LLM context may improve retrieval, but adds token cost,
provider dependency, and governance work. All improvement, citation-preservation,
latency, and cost claims are `NOT MEASURED`.

## Evidence

- [Contextual retrieval cost report](../experiments/M2-contextual-retrieval-cost.md)
- [M2 retrieval engineering summary](../experiments/M2-retrieval-engineering.md)
