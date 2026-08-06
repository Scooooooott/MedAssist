# deid-svc

`deid-svc` detects and anonymizes PHI. M0 provides only the service skeleton and gRPC health endpoint; Safe Harbor detection and replacement begin in M1.2.

## Concurrency Model

The service uses a synchronous gRPC server with an explicitly configured worker pool and maximum concurrent RPC count. M0 keeps conservative placeholders. Final tuning belongs to M5.11 after PHI detection latency measurements are available.
