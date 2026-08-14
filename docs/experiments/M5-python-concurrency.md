# M5 Python Concurrency Experiment

## Result Status

**NOT MEASURED.** This repository does not contain the approved BGE/reranker,
Presidio/spaCy, or Docling assets needed to produce honest relationship curves.
No P95, P99, throughput, RSS, 50 ms de-identification result, or final D25
worker count is claimed from synthetic unit tests.

The bounded runtime and instrumentation are implemented. The following is the
executable procedure for producing the missing evidence once approved,
non-PHI assets are mounted.

## Required Inputs

- Immutable model/tokenizer paths and hashes for `model-svc`.
- Pinned Presidio and spaCy model versions plus a non-secret benchmark salt.
- Approved synthetic or de-identified TXT/Markdown/PDF parser fixtures.
- A load generator such as `ghz`, Prometheus-compatible metric capture, and a
  process RSS sampler.
- An otherwise idle host with CPU model, logical-core count, RAM, Python, uv,
  ONNX Runtime, and operating-system versions recorded.

Never place raw clinical text, source document content, salts, credentials, or
storage tokens in benchmark commands or result files.

## Prepare Environments

From each service directory, resolve the checked-in lock and run its quality
gate before measuring:

```powershell
uv sync --frozen --group dev
uv run ruff check src tests
uv run mypy src tests
uv run pytest -q
```

For parser PDF runs, use `uv sync --frozen --group dev --extra docling`. Start
one service at a time with explicit settings. Example model baseline:

```powershell
$env:MEDASSIST_GRPC_WORKERS='4'
$env:MEDASSIST_GRPC_MAX_CONCURRENT_RPCS='12'
$env:MEDASSIST_QUERY_WORKER_THREADS='2'
$env:MEDASSIST_WORKER_THREADS='2'
$env:MEDASSIST_WORK_QUEUE_CAPACITY='8'
$env:MEDASSIST_RUNTIME_INTRA_OP_THREADS='1'
$env:MEDASSIST_RUNTIME_INTER_OP_THREADS='1'
$env:MEDASSIST_MODEL_PATH='X:\approved-models\bge-m3-int8.onnx'
$env:MEDASSIST_TOKENIZER_PATH='X:\approved-models\tokenizer.json'
uv run python -m model_svc.server
```

Use the analogous settings documented in each service README. Wait for the
gRPC health service to report `SERVING` before warmup or measurement.

## Load Matrix

Run every case after a fixed warmup and repeat it at least three times. Keep
request payload shape and duration constant while changing one variable.

| Service | Required matrix |
| --- | --- |
| `model-svc` query | query workers 1/2/4 x runtime intra-op 1/2/4 x concurrency 1/2/4/8/16; one text per RPC |
| `model-svc` passage | passage workers 1/2/4 x batch size 1/4/8/16 x concurrency 1/2/4/8; fixed safe text lengths |
| `deid-svc` | workers 1/2/4 x queue 0/4/8 x concurrency 1/2/4/8/16 using a fixed synthetic PHI fixture |
| `parser-svc` | workers 1/2/4 x queue 0/4/8 x concurrent documents 1/2/4/8 for each supported format |

For every cell, record total requests, successes, explicit rejections, errors,
requests/second, P50/P95/P99, queue-wait P50/P95/P99, execution P50/P95/P99,
CPU utilization, baseline RSS, peak RSS, and model warmup time. Confirm that
`model-svc` has one operating-system process and one resident copy of each
configured model bundle.

## Executable Request Procedure

Use repository protobuf files with `ghz`. Replace only the synthetic payload
and target port; do not include production text. A query run has this form:

```powershell
ghz --insecure --proto ../../contracts/proto/medassist/contracts/v1/model.proto `
  --import-path ../../contracts/proto `
  --call medassist.contracts.v1.ModelService/Embed `
  --data '{"texts":["synthetic query"],"inputType":"EMBEDDING_INPUT_TYPE_QUERY"}' `
  --concurrency 8 --total 5000 --format json 127.0.0.1:9003
```

Repeat with `EMBEDDING_INPUT_TYPE_PASSAGE` and fixed batch lengths. Use the
corresponding `deid.proto` and `parser.proto` RPCs for their matrices. Parser
fixtures must already exist in the configured test object store.

During each run, scrape `http://127.0.0.1:9101/metrics`, `:9102/metrics`, or
`:9103/metrics` as appropriate. Export traces only through the approved local
Collector. Verify that `python.queue_wait` and
`python.inference_execution` explain the observed latency change and inspect
exported attributes for forbidden text before retaining any trace artifact.

## Acceptance and Decision Rules

- Reject configurations where throughput falls while worker or runtime thread
  counts rise; this is evidence of oversubscription.
- Query embedding must show no batching-window delay and must reject promptly
  when its zero-queue pool is full.
- Passage batching must improve throughput without consuming query capacity.
- De-identification overload must remain `RESOURCE_EXHAUSTED`, never success,
  and measured P95 at the selected target concurrency must be at most 50 ms.
- Parser selection favors throughput subject to bounded RSS and explicit
  rejection; it must not starve online model or de-identification workloads on
  the target host.
- Select the lowest worker/runtime-thread combination at the throughput knee,
  then rerun the full matrix to confirm P99 and memory stability.

After measurement, replace the `NOT MEASURED` status with dated tables and
curves, record asset hashes and host details, resolve D25, and update the M6.2
memory budget. Do not infer missing values or substitute deterministic-test
backend results for production measurements.
