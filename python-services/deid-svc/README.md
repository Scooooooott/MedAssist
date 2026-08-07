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

The service runs as a single process with a synchronous gRPC server. It does not
use asyncio for request handling. Worker threads and maximum concurrent RPCs
are configured through the shared `MEDASSIST_GRPC_WORKERS` and
`MEDASSIST_GRPC_MAX_CONCURRENT_RPCS` settings. Current values remain
conservative placeholders. Final tuning belongs to M5.11 after PHI detection
latency measurements are available.
