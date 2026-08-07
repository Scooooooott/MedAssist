# parser-svc

`parser-svc` exposes the protobuf `ParserService.ParseDocument` RPC and parses
objects addressed by `s3://` or `minio://` URIs into a structured intermediate
representation. TXT, Markdown, and HTML are handled by the built-in parser;
PDF uses the configured Docling backend when the optional `docling` extra is
installed. The RPC never accepts document bytes directly.

## Runtime configuration

The service reads the shared `MEDASSIST_` environment prefix. S3-compatible
access uses `MEDASSIST_S3_ENDPOINT_URL`, `MEDASSIST_S3_REGION`,
`MEDASSIST_S3_ACCESS_KEY_ID`, `MEDASSIST_S3_SECRET_ACCESS_KEY`, and optionally
`MEDASSIST_S3_SESSION_TOKEN`. `MEDASSIST_PDF_BACKEND` defaults to `docling`;
set it to `none` to make PDF requests fail closed with
`PDF_BACKEND_UNAVAILABLE`. Install the S3 runtime dependency with the normal
service environment and install the optional PDF backend with
`uv sync --extra docling`.

An S3-compatible MinIO deployment should provide an endpoint URL and normally
uses path-style addressing. The service does not log object contents or
document text.

## Concurrency Model

The service runs as a single process with a synchronous gRPC server. It does not
use asyncio for request handling. Worker threads and maximum concurrent RPCs
are configured through the shared `MEDASSIST_GRPC_WORKERS` and
`MEDASSIST_GRPC_MAX_CONCURRENT_RPCS` settings. Current values remain
conservative placeholders. Final tuning belongs to M5.11 after parser workload
measurements are available.
