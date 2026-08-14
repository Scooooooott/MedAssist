# ADR-026: Java Virtual Threads and Context Carrier

## Status

Accepted. Runtime capacity measurements are pending.

## Decision

Servlet request services use Java 21 virtual threads, and explicit asynchronous fan-out uses one
virtual thread per task through the context-aware `ExecutorFactory`. WebFlux Gateway remains on
Reactor Netty event loops. Virtual threads are not pooled, and every finite downstream resource has
an explicit limiter or short acquisition timeout.

`ingestion` is excluded until Spring Batch partitioning is measured. `ScopedValue` is also excluded
because it is preview-only on Java 21; the established ThreadLocal carrier is retained with strict
capture, validation, and cleanup.

## Consequences

- The old platform-thread ceiling no longer provides accidental backpressure.
- Gateway limits, tool/provider bulkheads, database pools, and Python queues are correctness
  controls, not optional tuning.
- M4.12 alternating-role tests must run whenever the execution model changes.
- Pinning, RSS, and bottleneck claims remain unverified until recorded under controlled load.
