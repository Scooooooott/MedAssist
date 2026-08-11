# M3 Implementation Review

Date: 2026-08-10
Status: PARTIAL; the repository is not ready to claim M3 complete.

## Delegated Work

The following bounded implementation tasks were delegated and then reviewed by the
main agent:

| Slice | Result | Verification |
|---|---|---|
| M3.1-C LLM gateway foundation | Accepted with residual integration work | Agent LLM tests, 9 passed |
| M3.6 tool execution and retrieval gRPC adapter | Accepted as an execution slice | Agent execution tests, 9 passed |
| M3.5 JDBC statement timeout | Accepted | Clinical-data focused tests, 3 passed |
| M3.9 LLM egress enforcement | Accepted as gateway enforcement | Agent LLM tests, 9 passed |

## Accepted Capabilities

- Ingress de-identification is fail-closed and carries an original-query SHA-256
  correlation hash without storing the raw query in `AgentState`.
- Agent state, checkpoint projection, explicit node transitions, retry limits,
  termination reasons, and trajectory metadata are implemented.
- Role-scoped routing and tool allowlists are enforced before tool execution.
- `MIXED` policy and clinical searches execute with independent timeouts,
  cancellation, and partial-failure reporting.
- Retrieval gRPC requests carry trace, request, actor, and role metadata. The
  adapter fixes document-type filters and never exposes `includeSuperseded`.
- Backend chunk text is discarded by the persistence projection; only safe
  metadata and aggregate columns cross into state.
- SQL validation, allow-listed views, researcher aggregate restrictions,
  k-anonymity suppression, row limits, and JDBC statement timeout are covered by
  focused tests.
- HAPI FHIR R4 parsing, profile checks, resource-level quarantine, and Safe Harbor
  mapping foundations are present.
- Egress scanning and prompt-injection detection foundations exist. The LLM
  gateway now checks system and user prompt payloads before a provider call and
  fails closed with generic error messages.
- Retrieval no longer declares the OpenAI starter. It uses the provider-neutral
  Spring AI chat client plus explicit Jackson 2 dependencies required by the
  existing code.

## Remaining M3 Gaps

These are implementation gaps, not documentation inconsistencies:

1. M3.1-A is incomplete. Generation, prompt assembly, answer DTOs, and the old
   answer endpoint remain in `retrieval`; they have not been migrated to the
   `agent` state machine. The provider dependency is isolated, but the generation
   boundary is not yet moved.
2. M3.1-D/E is incomplete. No agent-side Advisor chain or ChatMemory retention and
   trimming policy is wired. The required inner/outer Advisor ordering is not
   executable or integration-tested.
3. The default agent `DraftGenerator` is still fail-closed and the default verifier
   rejects drafts. There is no enabled end-to-end answer path through the agent.
4. M3.4 has no durable clinical-data persistence, Spring Batch FHIR import job,
   idempotency store, failure quarantine table, or researcher aggregate SQL views.
   The current importer explicitly stops at an in-memory result adapter.
5. M3.5 has a safe query boundary, but a production read-only database role,
   database view migrations, and a wired structured-query tool backend are still
   missing. `structured_query` therefore remains fail-closed unless injected.
6. M3.6 uses `ForkJoinPool.commonPool()` by default. Context propagation and
   production concurrency isolation need a later integration slice; production
   retrieval calls now also carry the configured gRPC deadline.
7. M3.7/M3.8 citation span validation and bounded retry exist primarily in
   `retrieval`; they are not connected to an agent-side outer citation Advisor and
   retry checkpoint loop.
8. M3.9/M3.10 security controls are not yet wired to every tool-output and history
   path. Prompt-injection detection is a detector foundation, not a complete
   marked-and-audited tool-output pipeline or red-team suite.
9. M3.11 has trajectory records and metric calculators, but no Spring AI MCP server
   exposing only policy/clinical tools, no trajectory mode in the eval harness, and
   no holdout-v3 trajectory gate in CI. `structured_query` is not exposed by MCP.

## Verification

Passed after the final integration fixes:

```text
mvn -pl services/clinical-data,services/retrieval,services/agent -am "-DforkCount=0" test
contracts: 1 test
clinical-data: 12 tests
retrieval: 80 tests
agent: 55 tests
total: 148 tests, 0 failures, 0 errors

mvn -pl services/clinical-data,services/retrieval,services/agent -am spotless:apply checkstyle:check
Spotless: passed
Checkstyle: 0 violations

uv run pytest -q -p no:cacheprovider --basetemp L:\\MedAssist\\.pytest-tmp
20 tests passed
```

The eval-harness run requires a workspace-local pytest base directory in this
environment because the default system temporary directory is access-restricted.

The earlier reactor test-compile failure was caused by generated contracts classes
not being packaged before the first direct agent test compile. Packaging contracts
and rerunning the reactor resolved it; no source-level workaround was kept.

## Acceptance Decision

The accepted slices are safe to retain as M3 foundations. M3 remains open because
the end-to-end agent generation path, Advisor semantics, durable FHIR/structured
data path, citation/retry integration, and MCP/eval-harness delivery are still
required before release or public deployment.
