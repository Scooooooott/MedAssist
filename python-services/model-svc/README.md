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
`MEDASSIST_BACKEND=onnx-int8`. Reranking is a separate production ONNX
sequence-classification backend; it never falls back to deterministic scores.

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
| `MEDASSIST_MAX_RESIDENT_EMBEDDING_MODELS` | `1` | Maximum concurrently resident embedding bundles; must be at least 1 |
| `MEDASSIST_RERANK_ENABLED` | `false` | Explicitly enable the M2 reranker; M1 keeps the contract stub disabled |
| `MEDASSIST_RERANK_PROFILE` | `online` | Selects the online or offline reranker bundle |
| `MEDASSIST_RERANK_ONLINE_MODEL_PATH` | unset | Online cross-encoder ONNX file |
| `MEDASSIST_RERANK_ONLINE_TOKENIZER_PATH` | unset | Online pair tokenizer JSON |
| `MEDASSIST_RERANK_ONLINE_MODEL_NAME` | `cross-encoder/ms-marco-MiniLM-L-6-v2` | Online response identity |
| `MEDASSIST_RERANK_ONLINE_MODEL_VERSION` | `unversioned` | Online immutable bundle/version identity |
| `MEDASSIST_RERANK_ONLINE_MAX_LENGTH` | `512` | Online pair sequence limit |
| `MEDASSIST_RERANK_ONLINE_BATCH_SIZE` | `8` | Online CPU inference batch size |
| `MEDASSIST_RERANK_OFFLINE_MODEL_PATH` | unset | Offline cross-encoder ONNX file |
| `MEDASSIST_RERANK_OFFLINE_TOKENIZER_PATH` | unset | Offline pair tokenizer JSON |
| `MEDASSIST_RERANK_OFFLINE_MODEL_NAME` | `BAAI/bge-reranker-v2-m3` | Offline response identity |
| `MEDASSIST_RERANK_OFFLINE_MODEL_VERSION` | `unversioned` | Offline immutable bundle/version identity |
| `MEDASSIST_RERANK_OFFLINE_MAX_LENGTH` | `512` | Offline pair sequence limit |
| `MEDASSIST_RERANK_OFFLINE_BATCH_SIZE` | `8` | Offline CPU inference batch size |
| `MEDASSIST_RERANK_MAX_CANDIDATES` | `100` | Hard per-request candidate cap |

The `Embed` contract accepts `EMBEDDING_INPUT_TYPE_QUERY` and
`EMBEDDING_INPUT_TYPE_PASSAGE`. Inputs are truncated to `max_length`, pooled
with the attention mask, and L2-normalized. The configured dimension must match
the ONNX output.

## Rerank profiles and assets

`Rerank` scores query/candidate text pairs, sorts them by descending score, and
returns 1-based ranks plus the actual configured model name and version. The
request is rejected when it exceeds `MEDASSIST_RERANK_MAX_CANDIDATES` (100 by
default). `online` is intended for the M2 query path and uses a lightweight
MiniLM-class cross-encoder; `offline` selects the larger BGE reranker for
evaluation. Both profiles require an externally mounted ONNX sequence-
classification model and a compatible `tokenizer.json`. ONNX Runtime and
tokenizers are imported lazily, CPU execution is selected explicitly, and a
real pair inference must pass warmup before health becomes `SERVING`.

The repository does not contain either reranker bundle. Missing assets,
optional runtime failures, invalid logits, and inference errors keep the
reranker unavailable or fail the request; no deterministic production fallback
is attempted. The deterministic reranker exists only for direct test injection.

## Embedding model registry

The service can run several embedding bundles in one process. Every configured
entry is pinned by `name`, `version`, `dimension`, `backend`, and `model_path`;
ONNX entries may also specify `tokenizer_path`, batching, prefixes, and an
explicit `enabled` flag. The JSON value is supplied through
`MEDASSIST_EMBEDDING_MODELS`. For example, a local test configuration can
describe three independent candidates as follows (the fake backend is only
permitted when `MEDASSIST_ALLOW_DETERMINISTIC_TEST_BACKEND=true`):

```json
[
  {"name":"medical-domain","version":"med-v1","dimension":1024,"backend":"deterministic-test","model_path":"/models/medical.onnx"},
  {"name":"general-multilingual","version":"multi-v2","dimension":768,"backend":"deterministic-test","model_path":"/models/multilingual.onnx"},
  {"name":"lightweight","version":"light-v3","dimension":1536,"backend":"deterministic-test","model_path":"/models/light.onnx"}
]
```

Startup preloads only the first enabled embedding entry, which is the default
readiness identity. Other exact `name@version` identities are loaded on demand.
The registry enforces the resident limit with lease-protected LRU eviction, so
an embedding backend cannot be unloaded while inference is using it. ONNX
sessions and tokenizers are released explicitly on eviction. A bundle load
failure affects only that exact identity and returns `FAILED_PRECONDITION`; it
never falls back to another registered model or the deterministic test backend.
The repository contains no production model assets, so missing bundles remain
`NOT_SERVING` and no performance or memory figures are claimed.

Production entries must use `onnx-int8` and externally mounted, immutable
bundles. The service never downloads weights and rejects floating identities
such as `latest`, `stable`, or `unversioned` in the multi-model registry.
Embedding requests select an exact identity with `model_name` in the form
`name@version`. The current protobuf keeps `model_version` out of
`EmbedRequest`; an old single-model request may omit the selector, while a
multi-model request may not. Unknown names and versions fail closed.

At startup every enabled embedding model is probed. A single failed load,
warmup, or output-dimension check makes the overall health state
`NOT_SERVING`; no candidate is silently substituted. The response always
reports the registered name, fixed version, and dimension, and the adapter
rejects output count, dimension, identity, or non-finite-value mismatches.

The three-candidate configuration is an experiment prerequisite only. This
checkout contains no three-model quality, latency, memory, or cost results;
those measurements must be produced from approved external model bundles and
the evaluation harness rather than inferred from configuration or fake vectors.

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
tests use the deterministic embedding and reranker backends only by direct
injection and verify the gRPC readiness/error paths without downloading model
weights. Reranker latency, memory, and quality targets from M2.2 have not been
measured in this checkout; there is no honest local P95 or context-precision
result until the external online/offline assets and evaluation harness are
supplied.
