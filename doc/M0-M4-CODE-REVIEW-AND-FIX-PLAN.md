# M0-M4 Code Review and Fix Plan

Date: 2026-08-11

Scope: implementation under `REQUIREMENTS-FULL.md` for M0-M4. This record
separates repository defects that can be corrected in the current checkout
from acceptance evidence that requires licensed data, model assets, Docker,
PostgreSQL roles, or an external provider.

## Baseline

The repository contains a substantial M0-M4 foundation: contract-first Java
and Python services, ingestion and retrieval foundations, Agent safety
boundaries, governance policy compilation, audit-chain domain code, a React
frontend, and a Django operations-console boundary.

The focused frontend, governance, ops-console, and module-level tests pass.
The root Maven test currently encounters three `@TempDir` cleanup errors in
`ContextualRetrievalServiceTest` because this sandbox denies access to the
host system temporary directory. A workspace-local Maven temp configuration
has not yet been committed; this is recorded as environment verification
noise rather than a production behavior defect.

## Defects To Fix

### F-01 [P1] Policy compiler emits invalid RLS SQL for views

`governance/policy-manifest.yaml` declares the three
`clinical_research_*_counts` relations as views. The compiler currently emits
`ALTER TABLE ... ENABLE ROW LEVEL SECURITY` and `CREATE POLICY` for every
relation. PostgreSQL does not support table RLS statements on views, so the
generated governance artifact cannot be applied to a clean database.

Fix direction: keep column-level `GRANT SELECT` generation for views, but
generate table RLS and row policies only for relations whose `kind` is a
table. Add a regression test that asserts view output contains grants but no
`ALTER TABLE ... ENABLE ROW LEVEL SECURITY` or `CREATE POLICY` for a view.

### F-02 [P1] MIXED partial failure incorrectly forces Agent abstention

`DefaultAgentToolExecutor` preserves successful projections when one of the
parallel tools fails, but returns a fail-closed result. The execution engine
only transitions to generation when `ToolExecutionResult.succeeded()` is true,
so a usable policy or clinical result is discarded and the Agent abstains.
This violates the explicit M3 partial-failure contract.

Fix direction: add a distinct partial-failure status and a `canGenerate()`
predicate. Continue to generation only when the result contains safe usable
evidence; retain the degradation reason in the result and trajectory. Keep
empty or fully failed results fail-closed. Add tests for one-success/one-fail
and both-fail cases.

### F-03 [P1] Aggregate view has no database-level minimum group size

The M3 clinical aggregate views expose `patient_count` directly and rely on
`ResearchViewService` or `JdbcStructuredQueryExecutor` to suppress rows. The
governance compiler grants view access to role database principals, so a direct
view query can bypass the Java suppression boundary.

Fix direction: add the requirement-aligned default `HAVING COUNT(DISTINCT
(source_id, patient_id)) >= 5` to each aggregate view and add migration
contract assertions. The application layer remains responsible for a stricter
configured threshold and audit/exemption behavior.

### F-04 [P1] Safe structured-query aggregation is dropped before generation

`ToolResultProjector` produces a safe `SafeToolResultProjection` containing
aggregation columns, but the Agent execution engine only applies candidate
metadata and transient text evidence to `AgentState`. A structured query that
returns aggregate columns without text chunks therefore cannot reach the
generation or verification nodes.

Fix direction: carry only the safe aggregation columns through the state and
its persistence projection, include them in the generation prompt, and add a
separate aggregate-only verification rule. Empty or unverified aggregate
output must remain fail-closed; chunk citation verification must not be
weakened.

## Findings To Record, Not Pretend To Fix In This Review

These are real gaps or acceptance blockers, but completing them requires
additional milestone work or external state:

- M1.14 still has infrastructure-only Testcontainers coverage; a live
  cross-language ingest-to-cited-answer run needs Docker, PostgreSQL, MinIO,
  sidecars, and safe fixtures.
- Licensed M1/M2 evaluation records, de-identification annotations, model
  bundles, and a configured LLM provider are absent; M1/M2 metrics therefore
  remain `NOT MEASURED`.
- M2.4 does not yet have a production API embedding adapter; the current
  registry supports local ONNX and deterministic test backends.
- M0 data acquisition is intentionally manifest-only until source-specific
  license-aware download and normalization workflows are approved.
- M3 Agent generation/Advisor/MCP wiring, durable checkpoint and chat-memory
  persistence, structured-query production adapter, and full hard-deadline
  enforcement remain incomplete.
- M4 Keycloak JWT/resource-server integration, PostgreSQL RLS/GRANT execution,
  persistent audit projection, real metrics data sources, and Django
  `manage.py check` against the dedicated read-only database role remain
  environment-level acceptance work.
- Retrieval and Agent HTTP boundaries still accept client-provided role fields
  until the shared authenticated request-context/PEP integration is delivered;
  public deployment remains blocked. This is tracked as a security integration
  item rather than silently claiming M4 authentication is complete.

## Repair Sequence

1. Correct policy compilation and add view/table regression coverage.
2. Correct MIXED partial-failure semantics and add Agent execution coverage.
3. Add database-level k-anonymity to the clinical aggregate migration and
   migration-contract coverage.
4. Carry safe structured-query aggregation through the Agent state and verify
   aggregate-only answers without treating them as text citations.
5. Run focused Java, governance, clinical-data, and Agent tests, then run the
   frontend and Python checks again.
6. Update this record with accepted fixes, residual risks, and the M5 gate.

## Accepted Fixes

- **F-01**: `scripts/governance/policy_compiler.py` now treats manifest
  relations with `kind: view` as grant-only objects. Table RLS and row policy
  statements remain generated for tables. Governance tests: 8 passed.
- **F-02**: Agent mixed-tool execution now uses `DEGRADED` when safe output
  remains after a partial failure, while empty or fully failed results remain
  `FAIL_CLOSED`. Agent focused coverage includes both paths.
- **F-03**: all three clinical research views in
  `V6__m3_clinical_safe_harbor_import.sql` enforce the default five-patient
  group floor in SQL, with migration contract assertions.
- **F-04**: safe aggregation columns now travel through `AgentState` and its
  `agent-state-v2` persistence projection. Generation emits a bounded,
  delimited aggregate section, and aggregate-only answers are accepted only
  with empty citations and a value present in the safe result. Text citation
  verification and prompt-injection blocking remain unchanged.

Verification after the fixes:

- Agent reactor: 74 tests passed.
- M4 reactor (`identity-policy`, `clinical-data`, `retrieval`,
  `audit-governance` and dependencies): 83 retrieval tests plus the other
  module suites passed; reactor build succeeded.
- Ingestion migration contract: 6 tests passed.
- Python services: deid 12, parser 18, and model 37 tests passed.
- Evaluation harness: 20 tests passed using a workspace-local pytest temp
  directory.
- Frontend: 30 Vitest tests passed, ESLint/type-check/build passed, and the
  production bundle remained within the configured budget.

## M5 Readiness Gate

M5 may be analyzed in parallel, but should not be claimed as implementation
ready until the M3/M4 security and runtime prerequisites are closed. At
minimum, the project needs: an authenticated request context bound to actor
and role, durable trajectory/checkpoint semantics, a wired Agent retrieval and
structured-query path, real database privilege/RLS evidence, and a reproducible
M1 baseline. M5 work can begin with design and isolated adapters, but the
gateway/event/observability implementation should wait for those contracts.
