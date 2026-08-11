# M1 Closeout and M2 Implementation Plan

Date: 2026-08-07

## Objective

Finish the executable M1 baseline before using it as the control for M2 retrieval experiments. M2
implementation may proceed in parallel only where it does not depend on unmeasured M1 behavior.
Measured claims remain blocked until the licensed corpus, production model bundles, Docker services,
and configured LLM/API providers are available. Reports must use `NOT MEASURED`; placeholder numbers
are forbidden.

## Current Gate

M1 is not at its exit gate. The blocking chain is:

1. The four ingestion steps are skeletons.
2. Retrieval has no gRPC server or PostgreSQL integration baseline.
3. Answer generation is deterministic and SSE emits one complete response.
4. Golden-set content, persistent evaluation runs, and a consumed `holdout-v1` result are absent.
5. No full MinIO-to-answer run has completed three times.

M2.1 code can be built after the M1 retrieval contract is stabilized, but no M2 improvement claim can
be accepted before the M1 baseline is measured.

## Architecture Decisions

1. **Hybrid retrieval:** use PostgreSQL full-text search first. The generated `tsvector` is based only
   on `chunk.text`. BGE sparse retrieval remains a future experiment.
2. **API evolution:** extend REST and protobuf contracts additively. Existing clients retain vector-only,
   no-rerank, active-only semantics when new fields are absent.
3. **Context boundary:** `chunk.text` becomes source-faithful de-identified text. Breadcrumb and generated
   context live in separate fields. Only embedding input may concatenate context and text.
4. **Vector dimensions:** retain the 1024-dimensional baseline table and add dimension-specific tables for
   768 and 1536 dimensions. Repository routing uses an explicit dimension allowlist; table names never come
   from request text.
5. **Chunk experiments:** use `chunking_strategy_id` and include it in uniqueness and retrieval filters.
   The production default remains `structure-v1` until measured evidence supports a change.
6. **Version review:** metadata extraction failures go to a dedicated `document_metadata_review` queue,
   not quarantine. The document may be stored but cannot become active evidence until reviewed.
7. **Staleness:** calculate staleness from `effective_date`; a missing date is `Unknown`, not silently fresh.
8. **Evaluation schema:** Flyway is the sole schema owner. Python tools may insert/query evaluation runs but
   never create or migrate business tables.
9. **Response caching:** implement the role-aware cache but keep complete-answer caching disabled by default
   until the M3 ingress/egress PHI controls exist. Query-embedding caching is enabled only for explicitly
   de-identified traffic. Tests exercise both caches and fail-open Redis behavior.
10. **CI evaluation:** PR CI uses a committed synthetic, source-free result fixture. Nightly and milestone
    jobs require explicitly provisioned corpus/models/providers and fail clearly when invoked without them.
11. **Rolling holdout:** golden v1 contains 140 dev and 60 `holdout-v1` records. The 100 M2 additions allocate
    70 dev, 15 `holdout-v2`, and 15 reserved `holdout-v3`, preserving 30 percent holdout overall. A holdout is
    marked consumed only after a real milestone run.

## Fine-Grained Work Packages

### Wave 1: M1 Executable Pipeline

| ID | Scope | Acceptance evidence |
|---|---|---|
| M1-A1 | Ingestion pipeline records and ports | Pure unit tests for state and error semantics |
| M1-A2 | MinIO discovery and SHA-256 classification | New/changed/unchanged tests |
| M1-A3 | Parser then de-identification orchestration | Ordering, timeout, fail-closed, quarantine tests |
| M1-A4 | Chunk, PHI prescan, and batch embedding | Metadata and partial-failure tests |
| M1-A5 | Transactional chunk/vector publication | Rollback and idempotency tests |
| M1-A6 | Batch listeners, restart, retry, skip, mutex | Spring Batch integration tests |
| M1-A7 | Retrieval gRPC adapter | REST/gRPC parity test |
| M1-A8 | Model/schema dimension startup check | Mismatch prevents readiness |

### Wave 2: M1 Generation and Evaluation

| ID | Scope | Acceptance evidence |
|---|---|---|
| M1-B1 | Spring AI structured generation adapter | Provider-neutral mock ChatModel tests |
| M1-B2 | Citation existence checks and abstention | Invalid spans removed; zero citations abstains |
| M1-B3 | SSE `delta`, `final`, `error` lifecycle | Streaming and interruption tests |
| M1-B4 | Frontend SSE and citation contract alignment | Component tests for every state |
| M1-B5 | De-identification evaluator | Deterministic JSON/Markdown without raw text |
| M1-B6 | Golden-set validator and span-to-chunk derivation | Two chunking configurations resolve spans |
| M1-B7 | Evaluation persistence and baseline report | Flyway-owned rows and reproducibility tuple |
| M1-B8 | Cross-service acceptance runner | Three consecutive runs when dependencies exist |

### Wave 3: M2 Retrieval Core

| ID | Scope | Acceptance evidence |
|---|---|---|
| M2-A1 | Raw-text FTS migration and lexical repository | Test proves context-only term cannot match |
| M2-A2 | Pure RRF fusion | Rank, weight, tie, and duplicate tests |
| M2-A3 | Retrieval modes and filter parity | Vector, lexical, hybrid repository tests |
| M2-A4 | CompletableFuture deadline and failure policy | Parallel timing and injected failure tests |
| M2-A5 | Reranker backends and gRPC | Online/offline identity and ordering tests |
| M2-A6 | Retrieval rerank integration | Timeout returns fused candidates |

### Wave 4: M2 Data, Models, and Governance

| ID | Scope | Acceptance evidence |
|---|---|---|
| M2-B1 | Context cost estimator and budget gate | LLM mode cannot run without approved estimate |
| M2-B2 | Rule and LLM context generators | Cache key and fallback tests |
| M2-B3 | Context backfill Batch step | Repeat run makes no duplicate LLM calls |
| M2-B4 | Multi-model registry and dimension routing | Bounded residency and isolated index tests |
| M2-B5 | Fixed and semantic chunkers | Sentence boundary and parameter tests |
| M2-B6 | Parameter-scan manifest generator | 27 isolated combinations |
| M2-B7 | Version extraction and chain maintenance | Unknown, superseded, withdrawn tests |
| M2-B8 | Version history/diff/staleness API and UI | API plus component tests |

### Wave 5: M2 Cache and Quality Gates

| ID | Scope | Acceptance evidence |
|---|---|---|
| M2-C1 | Query normalization and cache keys | Role/model/filter key isolation |
| M2-C2 | Redis caches and single-flight | 20 callers cause one downstream call |
| M2-C3 | Cache invalidation, metrics, fail-open | Redis outage does not fail retrieval |
| M2-C4 | 300-record metadata/split validator | Exact category and split counts |
| M2-C5 | Threshold gate and safe diagnostics | Deliberate regression exits non-zero |
| M2-C6 | PR/nightly/milestone workflows | Explicit dependency and cost behavior |
| M2-C7 | ADR and experiment index | Every decision linked to evidence |
| M2-C8 | Final holdout-v2 run | Real tuple recorded, then marked consumed |

## Verification Policy

Every code package requires formatting, static analysis, unit tests, and an integration test where its
boundary is material. Database behavior uses Testcontainers when Docker is available. External model,
corpus, and LLM checks use explicit acceptance commands and never fall back to deterministic production
behavior. The final audit maps every M1 and M2 exit-check item to `PASS`, `BLOCKED BY EXTERNAL ASSET`, or
`FAIL`; only real measured evidence can produce `PASS` for quality, latency, memory, cost, and holdout items.
