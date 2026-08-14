# Observability Runbook

## Delivery Status

The repository contains a local/demo OpenTelemetry Collector, Tempo, Prometheus, Alertmanager,
Grafana, SLO rules, alerts, and a provisioned runtime dashboard. Live infrastructure evidence is
`NOT MEASURED`. Do not describe the stack as production-ready until the checks in this runbook and
`docs/experiments/M5-tracing-baseline.md` have been executed with the complete topology.

## Start and Inspect

Set a non-default Grafana password, then combine the application, events, and observability files:

```powershell
$env:GRAFANA_ADMIN_PASSWORD = '<local-secret>'
docker compose `
  -f deploy/compose/compose.yml `
  -f deploy/compose/compose.events.yml `
  -f deploy/compose/compose.observability.yml `
  --profile full up -d
```

The current Compose application surface does not yet declare every Java service. A target shown as
down is not evidence of a Prometheus defect when its container was never started on the Compose
network. Start missing services with their normal local command or complete their M6 Compose
declarations before collecting end-to-end evidence.

| Surface | Local URL | Purpose |
| --- | --- | --- |
| Grafana | `http://localhost:3000` | Runtime dashboard and Tempo Explore |
| Prometheus | `http://localhost:9090` | Targets, rules, query, exemplars |
| Alertmanager | `http://localhost:9093` | Alert groups and receiver status |
| Tempo | `http://localhost:3200/ready` | Trace backend readiness; do not expose publicly |
| Demo alert webhook | `http://localhost:18080` | Logs alert payloads from Alertmanager |
| OTLP gRPC/HTTP | `localhost:4317` / `localhost:4318` | Local telemetry ingest |

Never put credentials, bearer tokens, queries, document text, generated answers, or source content
in commands, screenshots, ticket fields, alert annotations, metric labels, or trace attributes.

## Signal Path

1. Java and Python services emit W3C trace context and OTLP spans to `otel-collector`.
2. The Collector drops non-allowlisted attributes and non-allowlisted span-event attributes.
3. Tail sampling keeps errors, traces over one second, and the configured baseline percentage.
4. Accepted traces go to Tempo. The same spans generate bounded RED histograms with exemplars.
5. Prometheus scrapes Java Actuator, Python metric ports, Collector span metrics, and stack health.
6. Grafana provisions Prometheus and Tempo correlations; Alertmanager routes alerts through named
   receivers. The demo receivers log webhook payloads and are replaceable without changing rules.

`MEDASSIST_TRACE_BASELINE_PERCENT` defaults to `100` for demo. Reducing it does not affect the
error and slow-trace policies. Tail sampling is stateful; all spans for a trace must reach the same
Collector. Do not add Collector replicas without a trace-ID-aware load-balancing tier.

## Trace Verification

1. Check Prometheus targets and confirm application, Python, Collector, and Tempo targets are up.
2. Send one approved synthetic question through the authenticated generation-session path.
3. In Grafana Explore, query Tempo by the gateway service and select the trace.
4. Confirm HTTP, Java-to-Python gRPC, and asynchronous audit spans share the trace.
5. Confirm vector and lexical retrieval are sibling spans with overlapping time ranges.
6. Confirm Python queue-wait and inference-execution spans are separate.
7. Compare root-span duration with wall time and the critical path. Do not sum overlapping spans.
8. Open an exemplar from the stage-latency panel and confirm it navigates to that trace.
9. Inspect exported attributes using the fixed safety fixture. Any query, prompt, chunk, document,
   generated output, token, cookie, patient identifier, user ID, or raw session ID is a failure.

Trace-to-log navigation is intentionally not provisioned because this delivery does not include a
central log backend. Structured application logs still carry trace and span IDs; add Loki or an
approved equivalent only through a separate retention and access-control decision.

## Compliance Alerts

The following alerts are compliance stop conditions, not ordinary operations pages:

- `MedAssistPhiLeakageCanaryDetected`
- `MedAssistAuditChainIntegrityFailure`
- `MedAssistUnauthorizedToolAccess`
- `MedAssistComplianceSignalMissing`

When one fires:

1. Stop external traffic at the gateway immediately. Do not wait for an SLO calculation.
2. Preserve immutable audit events, relevant trace IDs, deployment metadata, and alert timestamps.
3. Do not paste raw payloads or sensitive text into the incident channel.
4. Identify the first affected build and time interval using hashes, IDs, and safe reason codes.
5. Restore service only after the owning security/governance test passes and the alert has resolved.

The three producer counters are mandatory metric contracts:

- `medassist_phi_leakage_canary_total`
- `medassist_audit_chain_integrity_failures_total`
- `medassist_security_unauthorized_tool_access_total`

If an owning service is healthy but its counter is absent for 15 minutes,
`MedAssistComplianceSignalMissing` fires. This prevents missing instrumentation from appearing as a
clean zero. Producer integration is outside this bounded deployment-only change and remains a
release blocker wherever the metric is not yet emitted.

## SLO Burn

For `MedAssistAvailabilityFastBurn`, `MedAssistAvailabilitySustainedBurn`, or
`MedAssistAnswerLatencyFastBurn`:

1. Check request volume before interpreting a ratio; low-volume windows can be noisy.
2. Use stage P95 and exemplar links to identify the first slow or failing stage.
3. Compare normal and degraded paths. A fallback can protect availability while exhausting latency
   or quality budgets.
4. Check Python queue wait, database pool saturation, circuit breakers, and audit lag.
5. Record the PromQL query, UTC range, traffic count, trace ID, and deployed commit.

SLO objectives and error budgets are defined in `docs/slo/runtime-slos.md`. Empty series are
`NO DATA`, not healthy values.

## Audit Transport

For growing lag or DLQ activity:

1. Confirm `audit-governance` and Redpanda health and inspect consumer-group assignment.
2. Compare processed, duplicate, DLQ-routed, current DLQ depth, buffer-depth, and
   buffer-rejected metrics. `medassist_audit_dlq_routed_total` is cumulative event activity;
   `medassist_audit_dlq_pending` is the broker-reported current backlog and must be synchronized
   by the DLQ operations adapter.
3. Do not increase partitions: the M5.2 global hash chain requires one ordered partition.
4. Preserve the failed event envelope and schema version without adding payload content to metrics
   or alert annotations.
5. Replay only through the idempotent consumer after the cause is corrected and DLQ handling is
   explicitly authorized.

An audit chain integrity alert takes precedence over lag remediation and requires the compliance
stop procedure.

## Resilience

For an open circuit breaker or a burst of timeout/retry/bulkhead metrics:

1. Identify the named component and its configured fallback semantics.
2. Confirm the response, trace, audit event, checkpoint, and metric use the same degradation code,
   affected stage, and fallback mode.
3. Never enable a plaintext de-identification fallback or allow-on-error policy decision.
4. Avoid raising concurrency before checking database, model, and downstream capacity.

## Python Capacity

For `MedAssistPythonCapacityRejected`, compare queue depth, queue-wait P95, execution P95, process
RSS, and readiness. Query/rerank and passage ingestion have separate bounded paths. Do not add
unbounded queues or multiply model workers without the memory measurements in the M5 Python
concurrency report.

## LLM Budget

For `MedAssistLlmBudgetNearLimit`:

1. Confirm provider, model, token direction, retry, and outcome dimensions remain low cardinality.
2. Compare estimated cost with the configured soft and hard limits.
3. Check whether provider failover or retries caused duplicate cost.
4. Do not expose prompts or generated text during investigation.
5. Adjust limits only through reviewed configuration; alert rules remain provider-neutral.

## Generation Sessions

The dashboard expects these M5.12 producer contracts:

- `medassist_generation_sessions_active`
- `medassist_generation_sessions_total{outcome}`
- `medassist_generation_session_duration_seconds_bucket`
- `medassist_generation_session_resume_total{outcome}`
- `medassist_generation_session_buffer_events`

M5.12 now emits the session metrics listed above. The end-to-end answer SLO remains `NOT MEASURED`
until a production-like run correlates session completion with answer quality and latency. Do not
substitute gateway connection duration for session completion duration because reconnect and replay
make them different quantities.

## Alert Receiver Changes

The demo webhook prints PHI-free alert payloads. For a real environment, replace the named
Alertmanager receiver in `deploy/observability/alertmanager/alertmanager.yml` with an approved
webhook or notification integration. Keep routing based on bounded `severity` and `class` labels.
Credentials must come from the deployment secret mechanism, not committed configuration.

## Production TODO

- Put authentication and TLS in front of Grafana, Prometheus, Alertmanager, Tempo, and OTLP ports.
- Move Tempo to durable approved storage and set reviewed trace retention.
- Set Prometheus retention and volume capacity from measured ingestion/cardinality.
- Size Collector tail-sampling memory from measured trace rate and decision delay.
- Add a trace-ID-aware Collector load-balancing tier before horizontal sampling replicas.
- Replace static scrape targets with the production discovery mechanism.
- Replace the demo webhook, configure ownership/escalation, and test delivery failure behavior.
- Back up Grafana/Prometheus configuration and restrict dashboard/data-source administration.
- Complete the tracing baseline and archive evidence before claiming M5.4 acceptance.

These items are operational deployment work. Their absence must remain visible in release
readiness records rather than being converted into estimated evidence.
