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

The service uses a synchronous gRPC server with an explicitly configured worker pool and maximum concurrent RPC count. Current values remain conservative placeholders. Final tuning belongs to M5.11 after PHI detection latency measurements are available.
