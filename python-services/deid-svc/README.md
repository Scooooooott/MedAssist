# deid-svc

`deid-svc` exposes the protobuf `Detect` and `Anonymize` RPCs. Production startup
uses the Presidio-backed analyzer and reports `NOT_SERVING` when its NLP model or
the required HMAC salt is unavailable. Initialization, inference, and policy errors
fail closed; the RPC response contains entity metadata only and never the original
entity value.

The regex backend is available only when `MEDASSIST_DEID_TEST_MODE=true` is set
explicitly for tests or local demonstrations. It is not a production fallback.

The current source tree does not bundle the Presidio NLP model or a clinical NER
model. Before production use, install and pin those runtime assets, then record the
model name and version in `policy_version`.

At minimum, install the configured spaCy model (for example
`en_core_web_sm`) in the service environment and provide `MEDASSIST_HMAC_SALT`.

## Concurrency Model

The service uses synchronous gRPC plus a bounded online worker pool; it does not
use asyncio. Defaults are four gRPC threads, eight concurrent RPCs, two backend
workers, and four queued work items. `MEDASSIST_GRPC_WORKERS`,
`MEDASSIST_GRPC_MAX_CONCURRENT_RPCS`, `MEDASSIST_WORKER_THREADS`, and
`MEDASSIST_WORK_QUEUE_CAPACITY` configure those limits. Native runtime threads
are explicit through `MEDASSIST_RUNTIME_INTRA_OP_THREADS=1` and
`MEDASSIST_RUNTIME_INTER_OP_THREADS=1`. Prometheus metrics are exposed on
`MEDASSIST_METRICS_PORT=9102` unless the port is set to zero.

When the worker pool is full, `Detect` and `Anonymize` return
`RESOURCE_EXHAUSTED`; plaintext is never passed through as a fallback. Health
becomes `NOT_SERVING` if either the analyzer or executor is unavailable. These
defaults are conservative and **NOT MEASURED** against the production Presidio
asset or the 50 ms target.
