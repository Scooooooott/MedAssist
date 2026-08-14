# M5 Tracing Baseline

## Purpose

Measure tracing overhead and verify that one complete answer can be reconstructed across gateway,
Java services, Python gRPC services, and asynchronous audit handling. This report is intentionally
unpopulated because the complete runnable topology and approved benchmark workload were not
available during configuration delivery.

## Fixed Comparison

Run the same immutable synthetic/de-identified workload twice:

1. tracing disabled at the SDK/exporter boundary;
2. tracing enabled with the committed Collector configuration and the same head-sampling setting.

Use at least one warmup and three measured repetitions. Keep service versions, model digests,
worker limits, database state, request rate, request corpus order, and host power settings fixed.
Record wall-clock throughput, P50/P95/P99, first-byte latency, CPU, RSS, Collector memory, exported
spans, dropped spans, and tail-sampling decisions.

## Required Trace Checks

- W3C `traceparent` crosses HTTP, gRPC, and Redpanda boundaries.
- `RequestMetadata.trace_id`, audit, feedback, checkpoint, and tracing IDs map to one trace.
- Vector and lexical retrieval spans are siblings and overlap in time.
- Python `python.queue_wait` and `python.inference_execution` are distinguishable.
- Key spans include gateway routing, authorization, de-identification, embedding, vector retrieval,
  lexical retrieval, rerank, tool call, LLM generation, citation verification, and egress guard.
- End-to-end wall time follows the critical path; overlapping child spans are not summed.
- A fixed malicious telemetry fixture containing query, prompt, chunk, document, token, cookie, and
  patient-like attributes is absent after Collector processing.
- A latency or error exemplar opens the matching Tempo trace without a trace ID metric label.

## Results

| Metric | Tracing off | Tracing on | Delta |
| --- | ---: | ---: | ---: |
| Throughput | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| P50 latency | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| P95 latency | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| P99 latency | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Application CPU | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Application peak RSS | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Collector peak RSS | N/A | NOT MEASURED | NOT MEASURED |
| Tail-sampling early drops | N/A | NOT MEASURED | N/A |
| Export failures | N/A | NOT MEASURED | N/A |

## Evidence Metadata

| Field | Value |
| --- | --- |
| Commit | NOT MEASURED |
| UTC interval | NOT MEASURED |
| Host CPU/RAM/OS | NOT MEASURED |
| Workload and request count | NOT MEASURED |
| Model names and digests | NOT MEASURED |
| Collector/Tempo versions | Collector 0.153.0; Tempo 2.10.7 configuration baseline |
| Sampling configuration | Errors + traces over 1 s + configurable probabilistic baseline |
| Trace screenshot or exported trace ID | NOT MEASURED |

## Exit Rule

M5.4 live verification remains open until the checks above pass and this file contains reproducible
evidence. A visible trace alone is insufficient if the attribute safety fixture, sibling-span
shape, correlation IDs, overhead, and exemplar navigation were not also verified.
