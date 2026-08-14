# System Concurrency Model

This is the M5 concurrency design. Configuration and automated invariants are implemented;
host-level measurements remain explicitly pending where the required runtime is absent.

## Java request path

- Servlet request services (`agent`, `retrieval`, `clinical-data`, `identity-policy`, and
  `audit-governance`) enable Spring virtual threads. `gateway` is WebFlux: the switch applies to
  application tasks, while Reactor Netty event loops remain non-blocking platform threads.
- Agent tool fan-out and retrieval branch work use one virtual thread per task through the shared,
  context-aware `ExecutorFactory`. Virtual threads are never pooled.
- `ingestion` is excluded until Spring Batch execution and partitioning are measured.
- Virtual threads are not an implicit limit. Explicit limits remain at the gateway, each critical
  downstream, database pool, gRPC server, and LLM provider.

## Python services

- `deid-svc` and `parser-svc` use separate bounded executors. De-identification overload fails
  closed; parser overload is a retryable ingestion failure.
- `model-svc` uses separate zero-queue query/rerank pools and a bounded passage-ingestion queue,
  while model weights load once per process.
- Worker counts, ONNX thread counts, and queue capacities are explicit settings. Calibration and
  unmeasured values are in `docs/experiments/M5-python-concurrency.md`.

## Context propagation

Authenticated `ExecutionContext` and trace metadata cross HTTP, gRPC, virtual-thread executors,
Spring Batch, scheduled tasks, and message consumers. Every adapter clears context after use;
missing context is fail-closed and residual context is an error. OpenTelemetry does not create a
second identity carrier.

## Fan-out and capacity alignment

The conservative target is five simultaneous end-user generation requests. One request can create
two tool calls, and each retrieval call fans out to vector and lexical database work. The stated
worst case is `5 requests x 4 connections = 20 connections`; Retrieval configures a 20-connection
Hikari pool and a 250 ms acquisition timeout. Clinical structured-query work has an independent
eight-call limit and eight-connection pool. Gateway and LLM limits reject before these resources.

This is a calculation baseline, not a throughput claim. M6.7 must vary target concurrency while
observing pool occupancy and sibling branch spans. If either retrieval branch permits more than ten
concurrent database calls, combined branch limits must be reduced or the pool and memory budget
increased together.

## Pinning and memory

No owned code performs blocking I/O while holding a `synchronized` monitor. In-memory chat memory
uses a monitor only around bounded list operations. Production executor creation is architecture
tested; only the shared context-aware factory may create executors.

Pinning event count and virtual-thread versus platform-thread RSS are **NOT MEASURED** until the load
environment is available. `ScopedValue` is not adopted on Java 21 because it is preview-only. The
ThreadLocal carrier remains bounded to task lifetime and is always cleared. The M4.12 alternating-
role test is the regression gate for identity leakage.

## CPU-only work

RRF fusion, citation span alignment, hashing, and policy matching remain inline CPU work. Virtual
threads do not accelerate them; moving them to another virtual executor would add scheduling without
capacity. CPU saturation is observed separately from downstream queue wait.

## Measurement gate

Before changing limits, record request rate, p50/p95/p99, active downstream calls, database pool
occupancy, queue wait versus execution, memory, rejected work, and JFR pinning events. The report
must identify the new bottleneck after each change; lower latency caused by skipping a safety or
quality stage is not an improvement.
