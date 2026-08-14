# M5 Native Image Comparison

## Scope

Targets: `gateway` and `identity-policy`. Baseline host, workload, and GraalVM 25 runner are not
available in the current development environment, so the required measurements are not fabricated.

| Service | Metric | JVM | Native | Status |
|---|---|---:|---:|---|
| gateway | Resident memory | NOT MEASURED | NOT MEASURED | Pending scheduled run |
| gateway | Cold start | NOT MEASURED | NOT MEASURED | Pending scheduled run |
| gateway | Build time | NOT MEASURED | NOT MEASURED | Pending scheduled run |
| gateway | Throughput | NOT MEASURED | NOT MEASURED | Pending scheduled run |
| identity-policy | Resident memory | NOT MEASURED | NOT MEASURED | Pending scheduled run |
| identity-policy | Cold start | NOT MEASURED | NOT MEASURED | Pending scheduled run |
| identity-policy | Build time | NOT MEASURED | NOT MEASURED | Pending scheduled run |
| identity-policy | Throughput | NOT MEASURED | NOT MEASURED | Pending scheduled run |

## Reproducible Procedure

1. Run the regular JVM artifact and the native artifact separately on the same otherwise-idle host.
2. Record cold start from process creation to readiness, and RSS after five minutes idle.
3. Drive the same authenticated request corpus for ten minutes after a JVM warm-up period.
4. Record throughput and p50/p95/p99 latency, plus build wall time from a warm dependency cache.
5. Run each mode at least three times and report the median. Include the commit, CPU, memory,
   operating system, GraalVM, and container image digest.

Native throughput is not assumed to exceed a warmed JVM. The result is a deployment tradeoff, not
an optimization claim.
