# M5 Resilience Fault-Injection Report

- Milestone: M5.9
- Report date: 2026-08-11
- Local environment: Windows, Java 21, Docker daemon unavailable to the test process
- Overall status: **PARTIAL - fixture gates pass; production integration evidence is incomplete**

## Scope and Evidence Rules

The M5.9 suite contains a deterministic injectable-fake layer and a Testcontainers/Toxiproxy
network layer. A fake proves the expected state transition and recovery contract, but it does not
prove production dependency wiring. A skipped container test is recorded as `NOT EXECUTED`.

No real PHI, prompt, answer, source chunk, credential, or token was used. Test values are synthetic
identifiers only.

## Local Execution Summary

| Check | Result |
|---|---|
| PR hard gate | **PASS** - 4 tests, 0 failures, 0 errors, 0 skipped |
| Nightly-tagged suite | **PASS WITH SKIP** - 23 tests, 0 failures, 0 errors, 1 skipped |
| Deterministic scenario matrix | **PASS** - all 14 requirement rows and recovery assertions executed |
| Degradation-code fixture | **PASS** - 6 allowed-degradation rows use one code across response, trace, trajectory, and audit fixture fields |
| Production wiring guards | **PASS** - 2 guards confirm degradation projections and the Protobuf/Schema Registry contract |
| Toxiproxy network test | **NOT EXECUTED** - Docker was unavailable; JUnit recorded one skipped test |
| Checkstyle | **PASS** - 0 violations |
| Compose model | **PASS** - `docker compose ... config` parsed the faults profile |

The GitHub Actions workflow runs the four hard fail-closed tests for pull requests. Scheduled and
manual nightly runs execute the full tagged suite on an Ubuntu runner, including Toxiproxy when its
Docker runtime is available. No GitHub Actions run result exists yet, so CI execution is
`NOT EXECUTED` in this report.

## Scenario Matrix

| Scenario | Expected behavior | Fixture | Recovery fixture | Real service/network |
|---|---|---|---|---|
| deid-svc unavailable | Reject; no plaintext pass-through | PASS | PASS | NOT EXECUTED |
| deid-svc timeout | Reject at deadline | PASS | PASS | NOT EXECUTED |
| PDP unavailable | All five PEPs deny | PASS | PASS | NOT EXECUTED |
| Keycloak unavailable | Issued token remains valid; new login fails | PASS | PASS | NOT EXECUTED |
| embedding unavailable | Retrieval fails explicitly | PASS | PASS | NOT EXECUTED |
| rerank unavailable | Preserve order and emit `RERANK_BACKEND_ERROR` | PASS | PASS | NOT EXECUTED |
| parser unavailable | Step fails, document quarantined, committed data unchanged | PASS | PASS | NOT EXECUTED |
| Redis unavailable | Bypass cache; preserve functional result | PASS | PASS, including rebuild/hit | NOT EXECUTED |
| Redpanda unavailable | Buffer audit; preserve main path | PASS | PASS, including flush | NOT EXECUTED |
| Postgres slow query | Return `DATABASE_TIMEOUT` within deadline | PASS | PASS | NOT EXECUTED |
| LLM provider 429 | Bounded failover succeeds | PASS | PASS | NOT EXECUTED |
| all LLM providers unavailable | Explicit failure; no generated content | PASS | PASS | NOT EXECUTED |
| database pool exhausted | Immediate bulkhead rejection | PASS | PASS | NOT EXECUTED |
| vector or lexical branch timeout | Vector fails; lexical emits explicit degradation | PASS | PASS | NOT EXECUTED |

## Network Injection Coverage

`ToxiproxyRecoveryTest` is executable and covers three transport fault classes against a real HTTP
container:

1. downstream latency beyond the client deadline;
2. 100 percent downstream packet loss;
3. disabled proxy connection refusal.

Each toxic is removed and a bounded eventual-success assertion proves recovery without resetting
the client or restarting the upstream. This test was **NOT EXECUTED locally** because Docker was not
available. The compose profile creates named proxies for de-identification, policy, Keycloak,
model, parser, Redis, Redpanda, Postgres, and two LLM stubs, but no full application drill has been
run through those endpoints.

## Degradation Visibility

The shared CSV fixture asserts code equality across response, trace, trajectory, and audit for:

- `RERANK_BACKEND_ERROR`
- `REDIS_CACHE_BYPASS`
- `AUDIT_LOCAL_BUFFER`
- `LLM_PROVIDER_FAILOVER`
- `LEXICAL_CHANNEL_FAILED`
- `DOCUMENT_QUARANTINED`

This is contract-fixture evidence only. Normal-path and degraded-path P95/P99 latency are
**NOT MEASURED**. Production-like latency and infrastructure evidence is still pending, although the
code-level multi-surface propagation guard is now accepted.

## Code-Level Status

### M5.3 projections

The production auto-configuration creates an `ObservedDegradationRecorder`. The implementation records
a safe metric, tags the current span, invokes the shared metadata-only audit client when enabled, and
publishes a bounded safe trajectory projection. Consequently:

- response-level structured degradation can be tested;
- the CSV fixture can protect code vocabulary;
- response/metric/current-span/trajectory/audit wiring has source and unit-test evidence;
- production response/trace/trajectory/audit/metric consistency remains **NOT MEASURED** until the
  Docker-backed stack is exercised with trace and audit evidence;
- the remaining work is infrastructure execution and evidence collection, not an unimplemented sink.

`M5RequirementGapGuardTest` positively asserts the auto-configuration, metric, span, audit, and
trajectory paths.

### M5.2 wire contract

The audit transport uses generated Protobuf, `KafkaTemplate<String, byte[]>`, and
`ByteArraySerializer`. The Redpanda compose profile registers the Protobuf subject with BACKWARD
compatibility. Codec round trips, invalid payload rejection, durable ordering, and recovery are
covered locally. A live registry compatibility run remains **NOT EXECUTED** without Docker.

`M5RequirementGapGuardTest.auditKafkaWireFormatUsesProtobufAndSchemaRegistryContract` keeps the
deployment contract visible in CI.

## Commands and Artifacts

```bash
mvn -f tools/integration-smoke/pom.xml -Dgroups=fault-pr test
mvn -f tools/integration-smoke/pom.xml -Dgroups=fault-nightly test
docker compose -f deploy/compose/compose.faults.yml --profile faults config
```

Surefire XML is under `tools/integration-smoke/target/surefire-reports/`. GitHub Actions uploads PR
and nightly reports for 30 days. The operational procedures are in
`docs/runbook/resilience.md`.

## Acceptance Decision

The bounded M5.9 harness, PR gate, nightly workflow, recovery fixtures, runbook, and report are
implemented. M5.9 cannot be declared fully accepted until:

1. a Docker-capable nightly run executes Toxiproxy successfully;
2. representative full services are routed through the proxies and archived evidence is reviewed;
3. the Docker-backed multi-surface production consistency run passes;
4. live Protobuf/Schema Registry compatibility and recovery evidence is archived;
5. normal/degraded latency percentiles are measured with approved synthetic data.
