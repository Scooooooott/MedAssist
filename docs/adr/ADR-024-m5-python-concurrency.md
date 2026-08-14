# ADR-024: Bounded Python Concurrency and Safe Telemetry

## Status

Accepted for the bounded M5.4/M5.11 Python slice.

## Context

The three Python services perform blocking CPU or native-runtime work. An
asyncio server would still have to move that work to an executor. Unbounded
executors hide overload in memory and queue latency, while multiple model-svc
processes duplicate the largest model weights. Query embedding and ingestion
embedding also have different latency goals.

Real model bundles and the approved benchmark corpus are not present in this
checkout. Therefore this ADR selects executable process and backpressure
semantics, but does not claim final tuning values.

## Decision

All three services retain synchronous gRPC. The gRPC dispatch pool, maximum
concurrent RPCs, backend worker count, queue capacity, native intra-op threads,
and native inter-op threads are explicit `MEDASSIST_` settings. Native thread
environment variables are applied before model initialization. ONNX sessions
also receive explicit intra-op and inter-op values.

| Service | Implemented process model | Backpressure behavior |
| --- | --- | --- |
| `model-svc` | One process and bounded inference thread pools | Query/rerank uses a dedicated zero-queue pool; passage uses a separate bounded queue and the existing per-request ONNX batching |
| `deid-svc` | One process and a bounded online worker pool | Full capacity aborts with gRPC `RESOURCE_EXHAUSTED`; there is no plaintext fallback |
| `parser-svc` | One process and a bounded offline worker pool | Full capacity returns failed `ParseDocument` with `RESOURCE_EXHAUSTED` and `retryable=true` |

D26 is resolved for this slice by routing on `input_type`. Query work has no
batch wait or queue capacity. Passage requests may queue within a fixed bound
and batch their supplied texts using the configured ONNX batch size. Cross-RPC
coalescing is not enabled: it would change latency and trace-parent semantics
and requires real-asset evidence before adoption.

The worker executor captures `contextvars`, so the active OpenTelemetry context
crosses the thread boundary and is cleared with the captured context when work
ends. OpenTelemetry gRPC server instrumentation extracts W3C `traceparent`.
The API remains no-op safe without an SDK, exporter, or Collector.

Custom spans are limited to `python.queue_wait`,
`python.inference_execution`, and capacity rejection. Their attributes pass an
allowlist containing service, operation, queue, process model, worker count,
batch size, workload class, outcome, error type, and model name. Query text,
document text, storage URI, entity values, and generated text are forbidden.

Prometheus metrics expose request count/errors/latency, queue depth/wait,
execution latency, rejection count, and readiness. Labels are fixed service,
operation, outcome, queue, and process-model values; request IDs, trace IDs,
URIs, model input, and document identifiers are not labels.

Health is refreshed while the server runs. Model and de-identification services
report `SERVING` only when both their required backend and execution pools are
ready. Parser readiness includes its local parser and execution pool; a missing
optional PDF backend continues to fail only PDF work as designed.

## Consequences

- Model weights are loaded once in `model-svc`.
- Online query capacity cannot be consumed by queued passage ingestion.
- De-identification overload remains fail-closed.
- Queue wait and backend execution can be distinguished in traces and metrics.
- The current one-process de-identification and parser topology is a bounded
  baseline. Additional process replicas are a deployment and memory decision
  after measurement, not an unverified code default.
- D25 remains open until throughput, P95/P99, and resident-memory curves are
  measured with approved real assets.

## Deferred Operations

Collector/exporter configuration, Prometheus scraping, dashboards, production
ports, multi-process supervisors, replica counts, and final capacity values are
deployment work. They do not change the fail-closed or bounded semantics above.
