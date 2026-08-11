# M1 Closeout, M1-M2 Bridge, and M2 Acceptance Record

Date: 2026-08-09

## Verdict

The executable M1 closeout, the M1-M2 contract bridge, and the locally testable M2 implementation
are complete. Static analysis, unit tests, architecture tests, contract checks, frontend build and
responsive browser checks pass.

The M1 and M2 business exit gates are not fully closed. The licensed 300-record evaluation corpus,
production model bundles, configured LLM providers, and a running Docker daemon are unavailable.
Therefore corpus quality, end-to-end latency, memory, provider cost, three consecutive full-pipeline
runs, and the M2 `holdout-v2` result are `NOT MEASURED`. No synthetic number is presented as measured
evidence.

## M1 Closeout

The former ingestion skeleton is now an executable four-stage Spring Batch pipeline with durable
de-identified staging, retry and skip policies, a shared job mutex, restart support, transactional
publication, quarantine routing, PHI scanning, context caching, and ingestion audit records. Batch
controllers delegate lifecycle operations through `IngestionJobService`, preserving the controller
architecture boundary.

The parser, de-identification, and model gRPC services use generated protobuf contracts consistently.
Container builds now generate the Python stubs before runtime packaging, and generated-module discovery
works from both repository and container layouts. Python services are strict-type checked and the shared
package publishes its `py.typed` marker.

Retrieval generation, SSE lifecycle handling, citation existence checks, abstention behavior, frontend
stream handling, and evaluation persistence are implemented and covered by tests. Real baseline quality
and three consecutive MinIO-to-answer runs remain blocked by external runtime assets.

## M1-M2 Bridge

The additive REST and protobuf contracts retain baseline defaults while exposing retrieval mode,
reranking, contextual mode, model selection, chunking strategy, version state, staleness, and citation
metadata. The source-faithful `chunk.text` boundary is preserved: generated context is separate,
lexical search, reranking, final context assembly, LLM input, citation validation, and display use
only original text; only embedding input may combine context with text.

Flyway remains the only business-schema owner. Migrations add version metadata, context state,
strategy identity, evaluation persistence, and allowlisted vector-dimension tables without permitting
request-derived table names. Evaluation labels remain source-span based instead of chunk-ID based.

## M2 Requirement Status

| Requirement | Implementation status | Acceptance status |
|---|---|---|
| M2.1 Hybrid retrieval and RRF | PostgreSQL raw-text FTS, vector/lexical/hybrid modes, weighted RRF, filter parity, concurrent deadlines, and partial-failure policy implemented | Local tests pass; real dev-split improvement is `NOT MEASURED` |
| M2.2 Reranking | Online gRPC and offline/identity tiers, enable switch, timeout fallback, score ordering, model-version checks, default `topK=8`, and precision/nDCG report fields implemented | Local tests pass; context-precision and real latency/RSS improvement are `NOT MEASURED` |
| M2.3 Contextual Retrieval | Six-consumer source-text boundary, cache, rule/LLM generators, budget gate, and idempotent incremental backfill job implemented | Local tests pass; provider token usage and cost are `NOT MEASURED` |
| M2.4 Embedding model comparison | Model registry, bounded loading, explicit model selection, and isolated 768/1024/1536 routing implemented | Local tests pass; comparative quality, memory, and throughput are `NOT MEASURED` |
| M2.5 Chunking ablation | Structure, fixed-length, and semantic strategies plus isolated parameter-manifest/report tooling implemented | Script tests pass; real corpus ablation is `NOT MEASURED` |
| M2.6 Document freshness | Version chain, active-by-default retrieval, unknown/stale handling, review queue, history/diff API, and citation UI metadata implemented | Local API/component tests pass; production metadata review is not exercised |
| M2.7 Cache layer | Role/model/filter-aware keys, embedding and answer caches, TTLs, invalidation, metrics, fail-open Redis behavior, and single-flight implemented | Local tests pass; complete-answer caching stays disabled by default until M3 egress controls; M4 RBAC regression remains future work |
| M2.8 Evaluation and CI gates | Dataset validator, source-span derivation, threshold gate, trend persistence, safe diagnostics, CI fixtures, and experiment tests implemented | Harness passes; real 300-record corpus and nightly/milestone runs are `BLOCKED BY EXTERNAL ASSET` |
| M2.9 Experiment convergence | ADRs, experiment templates, reproducibility metadata, report index, and rolling-holdout protections implemented | `holdout-v2` remains reserved with consumption count 0; final model/strategy selection is `NOT MEASURED` |

## Verification Evidence

| Check | Result |
|---|---|
| Root `mvn verify` | PASS, 12 reactor modules, 216 tests, 0 failed/error/skipped; JaCoCo gates pass |
| Root `just lint` | PASS, including Java, frontend, three Python services, shared Python package, evaluators, and experiment scripts |
| Root `just test` | PASS, Java 216 + frontend 26 + Python/evaluation/experiment 102 tests |
| Frontend coverage | 88.40% statements, 75.75% branches, 94.54% functions, 91.40% lines |
| Parser service | 18 passed, 79.03% coverage |
| De-identification service | 12 passed, 76.57% coverage |
| Model service | 36 passed, 81.83% coverage |
| De-identification evaluator | 8 passed |
| Evaluation harness | 20 passed |
| M2 experiment scripts | 10 passed |
| Buf lint, breaking check, and generation | PASS |
| Frontend production build | PASS; gzip JavaScript 98,787 bytes and CSS 1,467 bytes |
| Browser QA | PASS at 1440x900 and 390x844; no overflow, overlapping controls, or console errors |
| Patch hygiene | `git diff --check`, language scan, and forbidden-data scan pass |
| Credential pattern scan | No AWS access key, private key, GitHub token, or Slack token pattern found; Gitleaks is not installed |

## Environment-Blocked Checks

Docker client 29.4.0 is installed, but the Docker Engine pipe is absent. The Testcontainers integration
smoke suite discovered two tests and skipped both, and the three Python service images could not be built.
Real PostgreSQL, Redis, MinIO, and cross-service container behavior is therefore not accepted by this
record.

The repository contains only evaluation metadata and a README under `data/eval`; it does not contain the
licensed records. `holdout-v2` has not been consumed. No configured production embedding/rerank bundle or
LLM provider is available, so quality deltas, P95 latency, peak memory, contextual-generation cost, and
milestone selection claims remain `NOT MEASURED`.

## Operational Notes

`just` now uses repository-local Maven, UV, Buf, and cache paths. Its `test` and `lint` recipes cover all
three Python services and the shared package in addition to Java, frontend, evaluators, and M2 experiment
scripts. CI generates Python protobuf contracts before service tests and runs commands from the owning
module configuration.

The frontend development server was browser-checked at `http://127.0.0.1:5173/`. Backend-dependent answer
quality and live latency were not evaluated because the service stack is not running.
