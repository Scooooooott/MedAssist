# M5 Java Concurrency Verification

## Implemented checks

- Virtual-thread switches are present on request-path services; Gateway's WebFlux exception is
  documented.
- Custom fan-out uses a context-aware virtual-thread-per-task executor.
- Architecture tests reject direct production thread-pool creation and virtual-thread pooling.
- Retrieval and clinical database acquisition timeouts are bounded.
- M4.12 context propagation and alternating-role tests remain in the Java suite.

## Measurements

| Measurement | Platform threads | Virtual threads | Status |
|---|---:|---:|---|
| Maximum stable request rate | NOT MEASURED | NOT MEASURED | Load environment pending |
| p95 / p99 | NOT MEASURED | NOT MEASURED | Load environment pending |
| RSS at target concurrency | NOT MEASURED | NOT MEASURED | Load environment pending |
| Pinning events | NOT MEASURED | NOT MEASURED | JFR/load run pending |
| First saturated resource | NOT MEASURED | NOT MEASURED | Stage spans/load run pending |

Run with `-Djdk.tracePinnedThreads=full` and capture JFR virtual-thread events. Correlate database
acquisition, downstream queue-wait, network, and computation spans; an endpoint average is not
sufficient evidence. Overload passes only when rejections rise promptly while global timeout rates
remain bounded.
