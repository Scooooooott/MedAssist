# model-svc

`model-svc` serves embeddings and later reranking. M0 provides only the service skeleton and gRPC health endpoint; ONNX model loading begins in M1.4.

## Concurrency Model

The service uses a synchronous gRPC server with an explicitly configured worker pool and maximum concurrent RPC count. M0 intentionally uses a single-process skeleton so future model weights are not loaded multiple times by accident. Final tuning belongs to M5.11.
