# M5 Implementation and Acceptance Record

Date: 2026-08-13

## Scope

M5 adds the platform controls required around the M0-M4 request path: the reactive gateway boundary,
durable audit transport, resilience semantics, distributed tracing, metrics and SLO artifacts, LLM
provider routing, conformance checks, native profiles, fault scenarios, concurrency controls, and
bounded resumable generation sessions.

## Implemented Areas

- **M5.1 Gateway:** WebFlux JWT/resource-server boundary, route-specific limits, Redis-backed atomic
  rate limiting, correlation validation, RFC 7807 responses, and PHI-free request logging.
- **M5.2 Audit:** generated Protobuf envelope, Redpanda/Schema Registry compose profile, bounded
  durable outbox, append-only hash chain with startup and periodic integrity checks, DLQ recovery,
  W3C trace propagation, and metadata-only shared audit client.
- **M5.3 Resilience:** typed component policies, circuit/bulkhead/time-limit/retry execution,
  structured degradation codes, safe metric and span projection, metadata-only audit projection, and
  bounded trajectory projection.
- **M5.4-M5.5 Observability:** Java/Python OTLP setup, collector tail-sampling configuration, Tempo,
  Prometheus, Grafana dashboards, alert rules, SLO/runbook contracts, and PHI-safe logging rules.
- **M5.6 LLM:** ordered provider routing, immutable model validation, egress re-check per destination,
  provider rate limiting, bounded 429 retry, conservative budget reservation/reconciliation, safe
  usage/cost metrics, and fail-closed all-provider behavior. The current budget ledger is explicitly
  single-replica; Redis-backed distributed accounting is a deployment prerequisite for scaling.
- **M5.7-M5.9:** HTTP/gRPC/Python contract checks, fault matrix, hard-fail-closed scenarios, recovery
  assertions, Toxiproxy profile, and operational runbooks.
- **M5.10-M5.11:** Java virtual-thread factories, bounded Python execution, explicit gRPC concurrency
  settings, context propagation helpers, and measurement templates.
- **M5.12:** Redis Stream-backed generation sessions, idempotent creation, ownership checks, bounded
  approved output, terminal-state reservation, replay/resume, cancellation, SSE helpers, and session
  metrics.
- **Compatibility and build hygiene:** Boot 4 Jackson 2 compatibility is supplied by `common-lib`,
  and the root dependency management pins the shared `okio-jvm` runtime required by OTel and MinIO.

## Local Acceptance

Passed during this review:

- `mvn -Djacoco.skip=true verify`: all 13 Maven reactor modules passed, including service context
  tests, contract tests, and 6 architecture-rule tests.
- Full Java module suites passed for `common-lib` (38), `audit-client` (24), and
  `audit-governance` (31); the remaining Java services also passed their full module suites in the
  same Reactor run.
- Python services passed: `deid-svc` (25), `model-svc` (66), and `parser-svc` (36), each above the
  configured 70% coverage gate.
- Frontend passed: 9 test files and 37 tests, `pnpm build`, `pnpm lint`, and Prettier check.
- `tools/integration-smoke`: 2 M5 production-wiring guard tests.
- Full integration-smoke passed 25 tests with 0 failures; 3 Docker-dependent tests were skipped
  because Testcontainers/infrastructure was unavailable in this environment.
- All M5 Compose profiles passed `docker compose config` expansion checks using local-only placeholder
  environment values; containers and external systems were not started.
- `mvn spotless:check` and `git diff --check` passed after normalizing the gateway POM line endings.

The Java verification used `-Djacoco.skip=true` to avoid stale pre-existing execution data after
source changes. Coverage thresholds should be regenerated from a clean workspace before release.

## Remaining Operational Evidence

These are intentionally recorded rather than fabricated:

- Docker-backed Redpanda, Redis, OpenTelemetry, Schema Registry, and Toxiproxy execution.
- Live P95/P99, RSS, Java pinned-thread, Python worker, and collector-overhead measurements.
- Native-image build and startup measurements for the supported service profiles.
- Distributed Redis budget ledger before horizontal `agent` deployment.
- A streaming provider adapter with an independently enforced first-token deadline. The current
  provider adapter is non-streaming and enforces the overall HTTP timeout.
- Real licensed corpus/model data, data-source permissions, and third-party LLM egress approval.

These items are deployment/evidence gates and do not change the local code-level acceptance above.
