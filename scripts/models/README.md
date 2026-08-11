# Model Asset Provisioning Verification

`verify_model_assets.py` is a local, verify-only gate for model bundles that
are provisioned outside this repository. It never downloads weights, contacts
license pages, imports ONNX Runtime, or performs inference. A service image or
deployment process may use the report before mounting a reviewed bundle.

## Usage

```powershell
python scripts/models/verify_model_assets.py `
  --manifest C:\medassist-models\manifest.json `
  --pretty
```

The command writes one JSON report to stdout. Exit code `0` means every asset
and every declared file passed. Exit code `1` means the manifest is invalid,
an asset is missing, a hash differs, metadata is incomplete, or a license/use
restriction is not approved. The report always contains `status`, per-asset
errors, expected and actual file hashes, and a `summary` object.

The report explicitly includes `verification_mode=verify-only`,
`downloads_attempted=false`, and `network_accessed=false`. These fields are
claims about this tool's behavior and are covered by unit tests; this tool has
no network client or download code.

## Manifest schema

The manifest is JSON with `schema_version=1` and a non-empty `assets` array.
Paths are local paths: a relative path is resolved relative to the manifest.
Remote URLs are rejected. The schema is intentionally strict so a misspelled
review or runtime field cannot silently pass.

Example production embedding declaration (the hashes below are placeholders
and must be replaced with hashes from the reviewed external bundle):

```json
{
  "schema_version": 1,
  "assets": [
    {
      "id": "embedding-bge-m3-prod-v1",
      "kind": "embedding",
      "model_name": "BAAI/bge-m3",
      "version": "2026-08-10-bge-m3-int8-v1",
      "backend": "onnx-int8",
      "files": [
        {"kind": "model", "path": "bge-m3/model.onnx", "sha256": "REPLACE_WITH_64_HEX_CHARS"},
        {"kind": "tokenizer", "path": "bge-m3/tokenizer.json", "sha256": "REPLACE_WITH_64_HEX_CHARS"}
      ],
      "metadata": {"dimension": 1024, "max_length": 1024, "quantization": "int8"},
      "license": {
        "status": "approved",
        "spdx_id": "Apache-2.0",
        "source_url": "https://reviewed-source.example/license",
        "reviewed_at": "2026-08-10",
        "redistributable": false,
        "third_party_llm_allowed": false
      },
      "usage": {
        "production": true,
        "allowed_environments": ["production", "offline-evaluation"],
        "restrictions": []
      },
      "purposes": ["M1.4 embedding", "M2.4 model comparison"]
    }
  ]
}
```

`onnx-int8` assets require exactly one model file and one tokenizer file,
fixed model name/version, SHA256 for each file, a positive embedding dimension,
an ONNX `max_length`, and `int8` quantization metadata. Reranker declarations
use `kind=reranker` and must provide a positive `metadata.output_dimension`
(normally `1` for a scalar relevance score).

## Deterministic test assets

`deterministic-test` is allowed only for unit or contract tests. It must have
`usage.production=false`, must not allow the `production` environment, and must
include the `non-production-only` restriction. A tiny fixture file is enough
for this verifier; it is not a model and must never be presented as a quality
or performance result. The verifier still checks its path and SHA256.

No production model, tokenizer, dataset, credential, or API key belongs in
this directory or in the repository. External provisioning must independently
record source revision, exporter/converter versions, dimensions, tokenizer
hash, license approval, and any third-party-LLM restriction before creating a
manifest accepted by this tool.

## Tests

The tests create temporary byte fixtures only; they do not contain or download
real model assets:

```powershell
python -m unittest discover -s scripts/models -p "test_*.py"
```
