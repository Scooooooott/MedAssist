# M0/M1 Architecture Audit

Date: 2026-08-07

## Scope

This audit compares the current implementation against the updated M0 and M1 requirements in `REQUIREMENTS-FULL.md` after the React frontend migration.

## Record

M0 is mostly implemented as an engineering foundation: Maven modules, contracts, shared domain model, Java quality tooling, Python service templates, local compose files, ADRs, architecture docs, and the React frontend scaffold are present.

M1 is partially implemented. Parser, de-identification, model-service, chunking, schema, retrieval foundation, answer API, evaluation-harness scaffolding, and the React frontend baseline exist, but the full M1 acceptance chain is not complete.

## Organized Findings

### M0 Gaps

| Severity | Requirement | Finding | Fix direction |
|---|---|---|---|
| High | M0 phase acceptance | `just bootstrap` is missing. | Add a bootstrap target and document it. |
| High | M0.7 | Java CI does not explicitly run Spotless and Checkstyle. | Add explicit CI steps before `mvn clean verify`. |
| High | M0.7 | `integration` job is a placeholder. | Add a minimal integration-smoke Maven project using Testcontainers for Postgres/pgvector and MinIO. |
| High | M0.9 | `just fetch-data` can appear successful while only creating folders. | Make full fetch explicit and require `--manifest-only` for the current safe scaffold mode. |
| Medium | M0.2 | 100 x 1024 vector payload round-trip test is missing. | Add a contracts serialization test. |
| Medium | M0.7/G9 | Commit-message CJK scan is missing. | Extend the language scanner with a commit-scan mode and wire it into CI. |
| Low | M0.5 | Python README concurrency details are present but can be more explicit. | Add exact process/thread/asyncio wording. |

### M1 Gaps

| Severity | Requirement | Finding | Fix direction |
|---|---|---|---|
| High | M1.5/M1.6 | Spring Batch stages are still skeleton steps. | Implement a real object-store -> parser -> deid -> chunk/embed -> index flow. |
| High | M1.10 | Answer generation is deterministic placeholder text and does not use `ChatClient`. | Implement a migratable generation component with structured output and citation validation. |
| High | M1.10/M1.11 | SSE currently sends one complete answer event. | Add `delta`, `final`, and `error` event lifecycle with true incremental output. |
| High | M1.9 | Retrieval gRPC server is missing. | Expose a gRPC retrieval service reusing the retrieval application service. |
| Medium | M1.1 | Parser PDF/object-store acceptance evidence is incomplete. | Add real PDF/object-store integration tests and coverage. |
| Medium | M1.2/M1.4 | Production Presidio/BGE assets are external and not acceptance-verified locally. | Add asset manifest, hash checks, and documented acceptance path. |
| Medium | M1.3 | De-identification evaluation dataset/report is missing. | Add annotation spec, safe evaluator, and baseline report template. |
| Medium | M1.8 | DB vector dimension startup validation is not implemented. | Add fail-fast schema/model dimension probe. |
| Medium | M1.9 | Retrieval baseline evidence and Testcontainers coverage are missing. | Add retrieval integration tests and `retrieval-baseline-v1` report. |

## Analysis

The M0 gaps are mostly build and process gaps that can be fixed now without destabilizing service behavior. They strengthen the baseline and avoid false-positive CI success.

The M1 gaps are feature-complete workflow gaps. They require additional implementation slices, real dependencies, or integration fixtures. Treating them as a quick patch would create misleading green status. The right next unit of work is a staged M1 completion plan starting with ingestion, because retrieval, generation, evaluation, and frontend end-to-end validation depend on indexed corpus data.

## Plan

Immediate fixes in this pass:

1. Add `just bootstrap`.
2. Make Java CI run Spotless and Checkstyle explicitly.
3. Replace the integration placeholder with a minimal Testcontainers-based integration smoke project.
4. Add the contracts vector payload round-trip test.
5. Make `fetch-data` fail closed unless `--manifest-only` is chosen.
6. Add commit-message language scanning.
7. Clarify Python service concurrency READMEs.

Deferred M1 work:

1. Complete M1.5/M1.6 ingestion end-to-end.
2. Add retrieval gRPC and integration baseline.
3. Replace placeholder answer generation with structured Spring AI generation.
4. Implement true SSE streaming.
5. Add golden/deid evaluation datasets, metrics, and acceptance reports.

## Applied Fixes

Completed in this pass:

1. Added `just bootstrap` and made buf use the workspace-local `.tools/buf-cache` directory.
2. Updated Java CI to run Spotless and Checkstyle explicitly.
3. Replaced the integration placeholder with a Testcontainers-based integration smoke project for pgvector and MinIO.
4. Added the contracts 100 x 1024 vector payload round-trip serialization test.
5. Changed `fetch-data` to fail closed unless `--manifest-only` is explicitly selected.
6. Added commit-message language scanning and wired it into CI.
7. Clarified Python service concurrency model wording in parser and de-identification READMEs.

Remaining after this pass:

1. The listed M0 process gaps are addressed.
2. The listed M1 workflow gaps remain deferred because they require new feature work across ingestion, retrieval, generation, streaming, and evaluation.
