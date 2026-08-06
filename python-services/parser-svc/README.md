# parser-svc

`parser-svc` parses documents into a structured intermediate representation. M0 provides only the service skeleton and gRPC health endpoint; parsing logic begins in M1.1.

## Concurrency Model

The service uses a synchronous gRPC server with an explicitly configured worker pool and maximum concurrent RPC count. M0 keeps conservative placeholders. Final tuning belongs to M5.11 after parser workload measurements are available.
