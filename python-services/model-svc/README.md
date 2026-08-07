# model-svc

`model-svc` exposes the `ModelService` protobuf API. Production defaults to a
configurable BGE-M3 ONNX int8 bundle. The process loads the model and tokenizer,
runs a real warmup inference, and only then reports the empty health service as
`SERVING`. Missing files, missing runtime packages, invalid output dimensions,
or warmup failures keep health at `NOT_SERVING`; `Embed` rejects requests with
`FAILED_PRECONDITION` instead of returning a deterministic fallback vector.

The deterministic backend is retained only for explicit unit/servicer injection.
Selecting it for a process requires both `MEDASSIST_BACKEND=deterministic-test`
and `MEDASSIST_ALLOW_DETERMINISTIC_TEST_BACKEND=true`. The default backend is
`MEDASSIST_BACKEND=onnx-int8`. `Rerank` is deliberately `UNIMPLEMENTED` in M1.4.

## Runtime configuration

The shared settings prefix is `MEDASSIST_`:

| Variable | Default | Meaning |
| --- | --- | --- |
| `MEDASSIST_BACKEND` | `onnx-int8` | Production backend selector |
| `MEDASSIST_MODEL_PATH` | unset | Path to the quantized ONNX model |
| `MEDASSIST_TOKENIZER_PATH` | unset | Path to `tokenizer.json` |
| `MEDASSIST_MODEL_NAME` | `BAAI/bge-m3` | Model identity returned in responses |
| `MEDASSIST_MODEL_VERSION` | `unversioned` | Immutable model bundle/version identity |
| `MEDASSIST_DIMENSION` | `1024` | Expected output dimension; warmup validates it |
| `MEDASSIST_MAX_LENGTH` | `1024` | Maximum tokenizer sequence length |
| `MEDASSIST_BATCH_SIZE` | `8` | Maximum inference batch size per ONNX call |
| `MEDASSIST_QUANTIZATION` | `int8` | Only int8 is accepted by the production backend |
| `MEDASSIST_QUERY_PREFIX` | empty | Optional query instruction/prefix |
| `MEDASSIST_PASSAGE_PREFIX` | empty | Optional passage prefix |

The `Embed` contract accepts `EMBEDDING_INPUT_TYPE_QUERY` and
`EMBEDDING_INPUT_TYPE_PASSAGE`. Inputs are truncated to `max_length`, pooled
with the attention mask, and L2-normalized. The configured dimension must match
the ONNX output.

## Conversion and quantization record

The repository does not ship clinical model weights. A production bundle must
be built and reviewed outside this source tree, then mounted through the two
path settings. Record these fields with every `model_version`:

- source model ID/revision, exporter and converter versions, git commit, and ONNX opset;
- tokenizer file hash, ONNX file hash, input/output names, max length, dimension, and pooling rule;
- quantizer implementation/version, int8 mode (dynamic or static), excluded operators, calibration dataset hash and sample count;
- original and quantized file sizes, output cosine agreement, retrieval recall delta, and acceptance threshold;
- CPU architecture, execution provider, thread settings, warmup time, RSS after load, and batch/sequence settings.

## Performance report fields

Benchmark each supported batch size with query and passage workloads and retain
the raw result. At minimum report request count, batch size, sequence length,
warmup count, p50/p95/p99 latency, throughput, CPU utilization, peak RSS,
model load time, and failed/not-ready request counts. Do not include source
text or other PHI in benchmark artifacts.

## Current local limitation

The current checkout contains no BGE-M3 ONNX/int8 model or tokenizer files, so
the default service intentionally starts `NOT_SERVING` until an external model
bundle and the optional runtime dependencies are installed. This is expected
fail-closed behavior, not a successful local inference baseline. The included
tests use the deterministic backend by direct injection and verify the gRPC
readiness/error paths without downloading model weights.
