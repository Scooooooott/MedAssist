# ADR-021: Micrometer Tracing and Shared Contract Conformance Fixtures

## Status

Accepted. The observability delivery surface is implemented for M5.4/M5.5; live end-to-end
evidence and production sizing remain pending.

## Decisions

### Tracing

Use Micrometer Tracing with the OpenTelemetry bridge on Java services, and OpenTelemetry Python
instrumentation on Python services. Export through the OpenTelemetry Collector. The W3C
`traceparent` is the transport boundary; `RequestMetadata.trace_id` is the application-level
correlation projection of the active trace.

The existing execution-context propagation mechanism remains the only identity propagation
model. Tracing must use an attribute allowlist and never record queries, chunk text, LLM output,
raw payloads, or high-cardinality tenant identifiers.

The local/demo topology uses one OpenTelemetry Collector Contrib instance and one Tempo
single-binary instance with local storage. The Collector applies a fail-closed attribute allowlist,
removes span-event attributes other than `exception.type`, performs bounded tail sampling, exports
traces to Tempo, and derives RED histograms through the `span_metrics` connector. Prometheus
scrapes application metrics and the Collector's OpenMetrics endpoint. Grafana provisions both
data sources and uses exemplars for metric-to-trace navigation.

Tail sampling keeps errors, traces slower than one second, and a configurable probabilistic
baseline. The demo default is 100 percent. Tail sampling is intentionally centralized: all spans
for one trace must reach the same Collector. Scaling requires a trace-ID-aware load-balancing
Collector tier before the sampling tier. The memory limit, decision wait, trace capacity, maximum
trace size, and decision caches are explicit. Their production values are not inferred from the
demo defaults.

Prometheus and Tempo retention are demo defaults, not records-management policy. Tempo has no
built-in authentication, so its port must not be published outside a trusted development host.
Production authentication, TLS, durable object storage, retention, replica topology, and capacity
are M6 deployment decisions.

### Contract conformance

Use a shared golden fixture suite under `contracts/conformance/` rather than Pact. Java client
tests and Python service tests consume the same normal, boundary, error, null-semantics, and
numeric-precision fixtures. This provides semantic coverage without adding a second cross-language
broker or test framework.

## Consequences

- M5.4 can add instrumentation without inventing a second trace ID or context carrier.
- M5.7 can run fixture validation in both language toolchains and in CI.
- Collector, Tempo, Prometheus, Alertmanager, and Grafana have version-controlled demo delivery
  configuration under `deploy/observability/`.
- The Collector is a second safety boundary for span attributes. Producers remain responsible for
  never creating sensitive telemetry in the first place.
- Error and slow-trace retention is configured, but its CPU and memory overhead is `NOT MEASURED`
  until the approved workload and complete service topology are available.
- Grafana covers runtime health, stage latency, audit transport, degradation, LLM budget, and
  generation sessions. M4 governance analytics remain separately owned and are not duplicated.
