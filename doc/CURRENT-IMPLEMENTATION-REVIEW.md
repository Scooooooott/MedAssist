# Current Implementation Review

Date: 2026-08-10

Scope: current repository state compared with `REQUIREMENTS-FULL.md`, covering
M0, M1, the M1-M2 boundary, and the implemented M2 foundation. This review
does not modify business code and does not attempt to resolve wording
inconsistencies between requirement iterations.

## Verdict

The repository contains a substantial, tested implementation of the M0/M1
platform and the M2 retrieval foundation. The Java, Python, frontend, contract,
and static checks recorded in [M1-M2 implementation and acceptance](M1-M2-IMPLEMENTATION-AND-ACCEPTANCE.md)
pass.

The M1 and M2 milestones are not acceptance-complete. There are two kinds of
remaining work:

1. Concrete implementation or operational defects, especially the Compose
   pipeline wiring and production-safe defaults.
2. Evidence blocked by external assets: licensed evaluation data, model bundles,
   a configured generation provider, and Docker/Testcontainers availability.

## Current Implementation Inventory

### M0 foundation

- Maven multi-module build with Java 21, Spring Boot, Spring AI, Spotless,
  Checkstyle, ArchUnit, JaCoCo, Enforcer, and reproducible local tool paths.
- Protobuf contracts, Buf lint/breaking checks, Java/Python generation, and
  contract round-trip tests.
- Shared domain records, Python service templates, structured logging,
  health/readiness handling, graceful shutdown, and explicit concurrency
  settings.
- Compose base infrastructure for PostgreSQL/pgvector, Redis, MinIO, bucket
  initialization, health checks, profiles, and local environment placeholders.
- React/TypeScript/Vite frontend foundation with lint, formatting, strict type
  checking, Vitest, accessibility-oriented components, and bundle checks.
- ADR, data-source, model-license, architecture, and cost-estimate records.

### M1 implementation

- Parser, de-identification, and embedding gRPC service adapters with explicit
  readiness and fail-closed behavior.
- Spring Batch ingestion stages for discovery, parse/de-identify, chunk/embed,
  and transactional indexing, including quarantine and PHI aggregate metadata.
- Structure-aware, fixed, and semantic chunking strategies with source ranges,
  breadcrumbs, token limits, table handling, and strategy identifiers.
- Flyway-backed document/version/chunk/embedding/review schema and JDBC
  retrieval with metadata/status/model filtering.
- REST/gRPC retrieval and answer-generation ports, citation validation,
  structured output, abstention, and SSE event handling.
- React answer streaming, Markdown rendering, citation inspection, source-span
  highlighting, timing/filter controls, and large-list virtualization fixtures.
- Evaluation harness, safe fixture tests, de-identification evaluation tooling,
  and M1/M2 report templates.

### M2 implementation

- PostgreSQL lexical search on source-faithful chunk text, vector/lexical/hybrid
  modes, weighted RRF, parallel branch deadlines, and failure classification.
- Online/offline reranker contracts and bounded degradation behavior.
- Rule-based and LLM-gated contextual retrieval, persisted context cache,
  approved-cost artifact validation, and six-consumer text policies.
- Multi-model embedding registry with dimension-isolated storage and bounded
  lease-protected residency.
- Version metadata review queue, document version governance, cache keying,
  Redis fail-open behavior, single-flight, metrics, and evaluation trend storage.
- Chunking ablation/evaluation command scaffolding and CI threshold machinery.

## Findings

Findings are ordered by impact. P1 means milestone blocking or unsafe in the
current default path. P2 means an important implementation or operational gap
that should be resolved before claiming the affected requirement.

### F-01 [PARTIALLY RESOLVED] Pipeline Compose does not provide the full retrieval chain

**Requirements:** M0.6, M1.5-M1.6, M1.14.

The ingestion overlay now supplies PostgreSQL, MinIO, and Python sidecar service
names through an explicit environment block. A retrieval image and service have
also been added to the `pipeline`/`full` profiles, including database, Redis,
model-service, and ingestion dependencies plus an application health check.

The remaining gap is the answer/agent path: the Compose profile still cannot
provide the complete MinIO-to-ingestion-to-retrieval-to-cited-answer flow. The
retrieval image and Compose syntax are locally verified, but a live run still
requires Docker, model assets, and a usable data fixture.

**Impact:** the pipeline profile can now resolve and start the ingestion and
retrieval containers, but the advertised full answer chain and the M1.14
end-to-end acceptance path remain unavailable from the provided entrypoint.

**Modification direction:** add the future agent/answer service to the profile,
make readiness checks exercise the complete application health contract, and
add a Compose/Testcontainers smoke path that proves one document reaches
chunks, vectors, retrieval, and a cited answer.

### F-02 [RESOLVED] The ingestion production default selected REDACT instead of SURROGATE

**Requirement:** M1.2 requires `SAFE_HARBOR_SURROGATE` as the default; REDACT is
for comparison experiments.

The running configuration, Java properties default, and no-argument processor
default now use `SAFE_HARBOR_SURROGATE`. `IngestionPropertiesTest` covers the
configuration default. `SAFE_HARBOR_REDACT` remains available as an explicit
comparison policy.

**Impact:** resolved in the current checkout; the default path now preserves the
required type-consistent and cross-document pseudonym behavior.

**Verification:** ingestion configuration and parse/de-identification regression
tests pass.

### F-03 [P1] M1.14 integration coverage is infrastructure-only

The current Testcontainers class has only two tests: PostgreSQL extensions and
MinIO bucket/versioning setup ([InfrastructureSmokeTest.java:26-84](../tools/integration-smoke/src/test/java/com/medassist/integration/InfrastructureSmokeTest.java:26)).
It does not start or mock the three Python services, submit a document to the
ingestion job, verify chunks/vectors, issue retrieval, validate citations, or
exercise answer generation. It also does not implement the required three
consecutive stable runs.

**Impact:** the passing unit suite does not establish the M1 cross-language
contract or end-to-end acceptance. In the current environment Docker is
unavailable, so the two smoke tests are skipped rather than executed.

**Modification direction:** split the suite into a fast infrastructure test and
an opt-in full-chain profile. The full profile should use real or explicitly
documented lightweight sidecars, upload a safe fixture, trigger the job, assert
database state and source ranges, then exercise retrieval and answer citations
three times.

### F-04 [P1] Required M1/M2 evaluation data and annotation assets are absent

`data/eval` contains the split manifest and README but no licensed 300-record
corpus. The repository explicitly records this at
[data/eval/README.md:14-17](../data/eval/README.md:14). The M1 de-identification
annotation directory is also not present, and the split metadata still reports
`holdout-v1` and `holdout-v2` with `consumption_count: 0`.

**Impact:** M1.3 de-identification metrics, M1.12 200-item QA/holdout-v1,
M1.13 RAGAS baseline, M2.8 300-record CI gates, and M2.9 holdout-v2 evolution
claims cannot be accepted. This is an external data prerequisite, not evidence
that the local evaluator is incorrect.

**Modification direction:** obtain and review the licensed records outside Git,
validate them with the evaluator, create the 60-100 record de-identification
annotation set, run the M1 baseline first, archive safe aggregate reports with
the version tuple, and consume holdout-v2 only once after M2 tuning.

### F-05 [P1] Production model and generation assets are not configured

The retrieval service defaults LLM generation to disabled and the provider/model
to `unconfigured` ([application.yml:56-63](../services/retrieval/src/main/resources/application.yml:56)).
The model service README records that the checkout contains no production BGE
bundle or reranker bundles and intentionally remains not ready without external
assets. The parser Docker build does not install the optional Docling extra, and
the de-identification Compose service does not provide the required clinical
NLP model or HMAC salt. Consequently, the current answer path is structurally
implemented but cannot produce an M1 production baseline, and reranker,
contextual, or parser/de-identification quality and latency cannot be measured.

**Modification direction:** provision immutable, licensed model bundles and a
reviewed local/provider configuration, record model identity and price metadata,
run warmup/readiness checks, then produce the M1 baseline before any M2
comparison. Keep the no-egress limitation explicit until the PHI egress guard is
implemented in M3.

### F-06 [P1] M2.4 has no API embedding backend

`EmbeddingModelConfig` accepts only `onnx-int8` and `deterministic-test`
([model_config.py:9-23](../python-services/model-svc/src/model_svc/model_config.py:9)).
The M2 requirement calls for three independently comparable embedding models,
with the third allowed to be an API model. The current registry can load several
local ONNX bundles, but it cannot route to an API provider. The example multi-
model configurations in the model-svc README use deterministic test entries,
which are not production candidates.

**Impact:** the M2.4 three-model comparison cannot be completed as specified by
configuration alone, even after local model bundles are supplied.

**Modification direction:** implement an explicitly selected, versioned API
embedding adapter with timeout/cost/readiness/error contracts, or record an ADR
that narrows the requirement to three local bundles before claiming M2.4.

### F-07 [PARTIALLY RESOLVED] The M1 baseline is not isolated from the M2 retrieval default

The retrieval application default remains `HYBRID`
([application.yml:37](../services/retrieval/src/main/resources/application.yml:37)),
while M1 requires a vector-only baseline and M2.1 introduces hybrid retrieval.
An explicit `m1-baseline` profile now selects `VECTOR_ONLY`, and a context test
locks that behavior down. The baseline profile is still not measured against
the licensed evaluation corpus.

**Impact:** a normal run still uses M2 `HYBRID`, so later M2 quality deltas
must explicitly activate and record the M1 profile to use a clean control.

**Modification direction:** run and archive the vector-only baseline using the
new profile, make it the documented control, and require every M2 report to
record retrieval mode, lexical configuration, RRF parameters, model tuple, and
commit.

### F-08 [P1] M2 experiment reports are scaffolds, not completed acceptance evidence

The current reports explicitly remain `NOT MEASURED`, including the M2 retrieval
summary ([M2-retrieval-engineering.md:3-4](../docs/experiments/M2-retrieval-engineering.md:3)),
the ten-chunk contextual cost gate
([M2-contextual-retrieval-cost.md:3](../docs/experiments/M2-contextual-retrieval-cost.md:3)),
and the 27-combination chunking ablation
([M2-chunking-ablation.md:4-7](../docs/experiments/M2-chunking-ablation.md:4)).

**Impact:** M2.1-M2.5 and M2.8-M2.9 have implementation/test scaffolding but no
measured quality, latency, memory, cost, category, or holdout evidence. No
default or ADR conclusion should be presented as empirically selected.

**Modification direction:** follow the reports in dependency order: M1 vector
baseline, hybrid/rerank, ten-chunk cost gate before LLM backfill, three-model
comparison, 27-run chunking matrix, then the 300-record PR/nightly/milestone
gate and one-time holdout-v2 run.

### F-09 [PARTIALLY RESOLVED] Spring Batch schema initialization bypassed the Flyway ownership rule

Ingestion now sets `spring.batch.jdbc.initialize-schema: never`
([application.yml:6-8](../services/ingestion/src/main/resources/application.yml:6))
and `V5__spring_batch_metadata.sql` owns the PostgreSQL Spring Batch metadata
tables and sequences. The requirements state that Flyway owns all schema
changes, so startup-time initialization is no longer allowed to create or
alter JobRepository tables outside the versioned migration history.

The code-level correction is complete. A clean PostgreSQL migration and
restart-persistence run still requires the unavailable Docker/PostgreSQL
runtime and remains external verification evidence.

**Modification direction:** run the clean-database migration and restart
metadata persistence test against PostgreSQL, then add the result to the
integration evidence record.

### F-10 [P2] `just fetch-data` is an intentional failing placeholder

The M0 data acquisition entrypoint returns exit code 2 unless
`--manifest-only` is supplied ([fetch_data.py:22-32](../scripts/data/fetch_data.py:22)).
It only creates directories and a source manifest; it does not implement a
re-runnable fetch/normalization workflow.

**Impact:** the M0 data acquisition deliverable is not executable, and the
README's `just fetch-data` command cannot be used to prepare the inputs needed by
M1.

**Modification direction:** implement source-specific, license-aware fetchers
with local caching, checksums, normalization manifests, retry/rate-limit rules,
and fail-closed handling for sources that cannot be redistributed. Keep raw
downloads outside Git.

### F-11 [RESOLVED] Python image builds silently fell back to unlocked dependency sync

The three Python Dockerfiles now use only `uv sync --frozen --no-dev`. A stale
or invalid lockfile fails the image build instead of resolving a different
dependency graph.

**Verification:** the three Dockerfiles contain no unlocked fallback command;
lockfile maintenance remains an explicit local operation.

### F-12 [PARTIALLY RESOLVED] Cache administration has no access control boundary

`CacheAdminController` is now disabled by default through
`medassist.retrieval.cache.admin-enabled=false`, and the default-context test
asserts that the controller is not registered. If explicitly enabled, it still
exposes unauthenticated `DELETE /internal/cache`
([CacheAdminController.java:11-28](../services/retrieval/src/main/java/com/medassist/retrieval/cache/CacheAdminController.java:11)).
The project correctly documents that public deployment is blocked before M3
PHI ingress/egress controls, but an internal destructive endpoint still needs a
network or authentication boundary before it is enabled beyond a trusted
local network.

**Modification direction:** bind the endpoint to an internal management surface
and add the future identity-policy authorization hook, with an integration test
that rejects external/unauthorized cache clears.

## Requirement Status Summary

| Area | Current state | Review result |
|---|---|---|
| M0 build, contracts, domain, quality tooling, ADRs, frontend foundation | Implemented and locally verified | Passing code checks |
| M0 Compose core infrastructure | Implemented | Core profile is present; pipeline wiring is not ready |
| M0 data acquisition | Manifest scaffold only | Incomplete |
| M1 parser/deid/model adapters | Implemented with unit/contract coverage | Production assets and metrics missing |
| M1 ingestion/chunk/index/retrieval/answer/frontend | Implemented and tested at unit/component level | Full-chain acceptance missing |
| M1 de-identification annotation and 200-item evaluation | No licensed/annotated data in checkout | Blocked |
| M1.14 integration and baseline | Infrastructure-only smoke test | Incomplete |
| M2.1-M2.7 code foundation | Implemented/tested at unit/config level | Requires experiment evidence; API embedding backend missing |
| M2.8-M2.9 evaluation and evolution | Gate/report scaffolding | Blocked by corpus and unmeasured reports |

## Verification Evidence

The following results are recorded from the current checkout:

- Latest focused closeout checks: ingestion `15/15`, retrieval `24/24`, and
  evaluation harness `20/20` passed; Java Spotless/Checkstyle, frontend
  build/lint/format, and Python focused test suites passed.
- The Docker client parses the `pipeline` Compose profile and resolves ingestion
  to `postgres`, `minio`, `parser-svc`, `deid-svc`, and `model-svc`; the Docker
  Engine itself remains unavailable for a live run.

- `mvn verify`: 12-module reactor, 216 Java tests, zero failures/errors/skips,
  and JaCoCo gates passed.
- `just lint`: Java, frontend, Python, language, and forbidden-data checks
  passed.
- `just test`: Java, frontend, de-identification evaluation, evaluation
  harness, and experiment tests passed.
- Buf lint, breaking checks, and generation passed.
- Browser QA passed at desktop and mobile viewports with no overflow or console
  errors.
- Docker/Testcontainers could not execute because the Docker engine was not
  available. Production model bundles, a licensed 300-record corpus, and a
  configured LLM provider were also unavailable.
- The safe-data scans and repository secret-pattern scan found no prohibited
  data or matching credentials. `gitleaks` is not installed locally.

## Priority Work Plan

1. Complete the Compose full-chain integration profile, including the future
   agent/answer service and live smoke path.
2. Execute the PostgreSQL migration/restart evidence and define the full-chain
   retrieval/answer Compose profile.
3. Run and archive the explicit vector-only M1 baseline measurement.
4. Provision and validate licensed corpus/annotations and immutable model assets.
5. Run and archive the M1 baseline, then execute the M2 controlled experiments in
   dependency order.
6. Implement or formally scope the M2 API embedding backend.
7. Protect cache administration with an internal management/authentication
   boundary before enabling the endpoint.

Until items 1-5 are complete, the repository should be described as a tested
implementation foundation rather than a fully accepted M1/M2 system.
