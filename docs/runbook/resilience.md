# Resilience and Fault-Injection Runbook

## Purpose

This runbook covers every M5.9 fault scenario. It separates deterministic semantic tests from
network injection and full-service evidence. Never put query text, document text, generated answer
content, tokens, credentials, or other PHI-bearing values into commands, logs, traces, or reports.
Use only synthetic identifiers when running a drill.

## Test Levels

| Level | Command | Evidence |
|---|---|---|
| PR compliance gate | `mvn -f tools/integration-smoke/pom.xml -Dgroups=fault-pr test` | Fail-closed semantics for de-identification unavailable/timeout, PDP unavailable, and all LLM providers unavailable |
| Nightly matrix | `mvn -f tools/integration-smoke/pom.xml -Dgroups=fault-nightly test` | All 14 semantic fixtures, recovery assertions, degradation-code fixtures, known-gap guards, and Toxiproxy network recovery when Docker is available |
| Manual proxy topology | `docker compose -f deploy/compose/compose.yml -f deploy/compose/compose.events.yml -f deploy/compose/compose.faults.yml --profile full --profile faults up -d` | Reusable Toxiproxy endpoints on the application network for full-service drills |

A skipped Toxiproxy test is `NOT EXECUTED`, not a pass. Fixture-level evidence does not prove that
production service wiring, telemetry exporters, or event serialization work end to end.

## Fault Proxy Endpoints

Applications under test must be explicitly configured to use the proxy endpoint. Starting the
compose file alone does not redirect traffic.

| Dependency | Container endpoint | Host endpoint | Proxy name |
|---|---|---|---|
| deid-svc | `toxiproxy:8666` | `localhost:18666` | `medassist_deid` |
| identity-policy | `toxiproxy:8667` | `localhost:18667` | `medassist_policy` |
| Keycloak | `toxiproxy:8668` | `localhost:18668` | `medassist_keycloak` |
| model-svc | `toxiproxy:8669` | `localhost:18669` | `medassist_model` |
| parser-svc | `toxiproxy:8670` | `localhost:18670` | `medassist_parser` |
| Redis | `toxiproxy:8671` | `localhost:18671` | `medassist_redis` |
| Redpanda | `toxiproxy:8672` | `localhost:18672` | `medassist_redpanda` |
| Postgres | `toxiproxy:8673` | `localhost:18673` | `medassist_postgres` |
| primary LLM stub | `toxiproxy:8674` | `localhost:18674` | `medassist_llm_primary` |
| secondary LLM stub | `toxiproxy:8675` | `localhost:18675` | `medassist_llm_secondary` |

Use the Toxiproxy API at `http://localhost:8474`. For example, inject 750 ms downstream latency:

```bash
curl -X POST http://localhost:8474/proxies/medassist_deid/toxics \
  -H 'Content-Type: application/json' \
  -d '{"name":"drill-latency","type":"latency","stream":"downstream","attributes":{"latency":750,"jitter":0}}'
```

Remove it and verify recovery:

```bash
curl -X DELETE http://localhost:8474/proxies/medassist_deid/toxics/drill-latency
curl http://localhost:8474/proxies/medassist_deid
```

Disable and re-enable a proxy to inject connection refusal:

```bash
curl -X POST http://localhost:8474/proxies/medassist_deid \
  -H 'Content-Type: application/json' -d '{"enabled":false}'
curl -X POST http://localhost:8474/proxies/medassist_deid \
  -H 'Content-Type: application/json' -d '{"enabled":true}'
```

## Scenario Procedures

| Fault | Symptom and impact | Diagnose | Recovery and required assertion |
|---|---|---|---|
| deid-svc unavailable | Request is rejected; no input passes to retrieval or an LLM | Check gRPC status, de-identification timeout/circuit metrics, and dependency health without logging input | Restore endpoint; next synthetic request succeeds with transformed text; no plaintext fallback occurred |
| deid-svc timeout | Request ends at the configured deadline with a safe explicit error | Check de-identification deadline and timeout count; confirm no broad retry | Remove latency; circuit returns closed after probes; next request succeeds without manual state reset |
| PDP unavailable | Gateway and all downstream PEPs deny | Check PDP health and deny decision metrics at gateway, retrieval, clinical-data, ingestion, and agent | Restore PDP; all PEPs authorize an allowed synthetic request; no permissive cached decision survived |
| Keycloak unavailable | Existing locally verifiable token remains valid; new login fails | Check issuer reachability and JWK cache age; do not disable JWT validation | Restore Keycloak; new login succeeds; independently validated existing token remains bounded by expiry |
| embedding unavailable | Retrieval fails explicitly; lexical-only must not silently activate | Check model gRPC health, embedding timeout/circuit state, and response code | Restore model endpoint; vector retrieval succeeds and circuit returns closed |
| rerank unavailable | Original candidate order is returned with `RERANK_BACKEND_ERROR` | Compare response degradation data and rerank failure metric; inspect safe trace attributes | Restore reranker; reranked order returns and no degradation is emitted |
| parser unavailable | Current document Step fails and document enters quarantine; committed documents remain unchanged | Check Batch Step status, quarantine metadata, and parser health | Restore parser; retry quarantined synthetic document; committed count advances once |
| Redis unavailable | Cache is bypassed and downstream is called; functional result remains available | Check cache bypass counter and Redis health; watch downstream load | Restore Redis; a miss repopulates cache and a subsequent call records a cache hit |
| Redpanda unavailable | Main path continues while audit events enter the bounded durable buffer | Check buffer depth/bytes, oldest age, broker health, and publish failures | Restore broker; buffer drains in order, offsets resume, duplicates stay deduplicated |
| Postgres slow query | Call returns `DATABASE_TIMEOUT` within its component deadline | Check database wait/statement metrics and safe query identifier, never SQL values | Remove latency or cancel cause; next bounded query succeeds; circuit closes automatically |
| LLM provider 429 | Bounded backoff/retry or alternate provider is used | Check provider, retry, failover, rate-limit, and budget metrics without payloads | Remove rate limit; preferred provider resumes according to routing policy |
| all LLM providers unavailable | Explicit `LLM_ALL_PROVIDERS_UNAVAILABLE`; no generated or uncited content | Check each provider health and failover attempts; confirm retrieval-only termination remains disabled | Restore one provider; a cited synthetic response succeeds; no stale candidate text was emitted |
| database pool exhausted | Bulkhead rejects immediately rather than queueing until a global timeout | Check pool active/pending values and bulkhead rejection count | Release capacity; next acquisition succeeds without restarting the service |
| vector or lexical branch timeout | Vector timeout fails retrieval; lexical timeout emits `LEXICAL_CHANNEL_FAILED` and vector-only results | Compare response, trace, trajectory, and audit degradation code; confirm no branch vanished silently | Remove delay; both branches participate again and degradation clears |

## Degradation Visibility

For every allowed degradation, collect the same safe code from:

1. the response contract;
2. trace attributes;
3. checkpoint or trajectory metadata;
4. the audit event;
5. the low-cardinality metric label, where configured.

The fixture in `tools/integration-smoke/src/test/resources/faults/degradation-surfaces.csv` enforces
four-surface code equality. It deliberately stores no content. Production code now projects the same
safe degradation code to metrics, the current span, the metadata-only audit client, and a bounded
trajectory projection. The fixture remains necessary because it protects the shared vocabulary without
requiring infrastructure.

## Known Requirement Gaps

### Production degradation projections

The production auto-configuration now creates `ObservedDegradationRecorder`. It increments a safe
low-cardinality metric, tags the current span, calls the metadata-only `DegradationAuditSink`, and
publishes a bounded `DegradationTrajectoryEvent`. The audit client maps only `reason_code`,
`content_domain`, `obligation`, and correlation identifiers; free-form reasons never cross that
boundary. `M5RequirementGapGuardTest` positively checks this wiring.

### Audit wire contract

The codec and Kafka value path use generated Protobuf bytes and `ByteArraySerializer`. The events
compose profile registers `audit-events-value` as a Protobuf subject and sets BACKWARD compatibility.
The local conformance and codec tests cover the payload contract; live registry compatibility and
broker recovery require the events profile and remain `NOT EXECUTED` when Docker is unavailable.

## Escalation and Evidence

Stop a drill immediately if unapproved content appears in any observable surface, if de-identification
or authorization fails open, if an audit buffer reaches its configured bound, or if recovery requires
discarding records. Archive Surefire XML, safe metric snapshots, trace IDs, proxy configuration, image
digests, commit ID, and timestamps. Record skipped or unavailable infrastructure as `NOT EXECUTED`.
