"""Verify externally provisioned model assets without downloading anything.

The manifest is deliberately independent from model-svc runtime configuration.
It verifies the immutable files and the review metadata that must be present
before those files are mounted into a service.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
TOOL_VERSION = "1"
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")
FLOATING_IDENTITIES = {"latest", "stable", "unversioned", "default", "main"}
ALLOWED_BACKENDS = {"onnx-int8", "deterministic-test"}
ALLOWED_KINDS = {"embedding", "reranker"}
ALLOWED_FILE_KINDS = {"model", "tokenizer", "fixture"}
ALLOWED_LICENSE_STATUSES = {"approved", "pending", "rejected", "unknown"}

ROOT_KEYS = {"schema_version", "assets"}
ASSET_KEYS = {
    "id",
    "kind",
    "model_name",
    "version",
    "backend",
    "files",
    "metadata",
    "license",
    "usage",
    "purposes",
}
FILE_KEYS = {"kind", "path", "sha256"}
METADATA_KEYS = {"dimension", "output_dimension", "max_length", "quantization"}
LICENSE_KEYS = {
    "status",
    "spdx_id",
    "source_url",
    "reviewed_at",
    "redistributable",
    "third_party_llm_allowed",
    "notes",
}
USAGE_KEYS = {"production", "allowed_environments", "restrictions"}


def _error(code: str, message: str, field: str | None = None) -> dict[str, str]:
    result = {"code": code, "message": message}
    if field is not None:
        result["field"] = field
    return result


def _unknown_keys(value: object, allowed: set[str], field: str) -> list[dict[str, str]]:
    if not isinstance(value, dict):
        return []
    return [
        _error("UNKNOWN_FIELD", f"unknown field '{key}'", f"{field}.{key}")
        for key in sorted(set(value) - allowed)
    ]


def _is_non_bool_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _validate_sha256(value: object, field: str) -> list[dict[str, str]]:
    if not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value):
        return [
            _error(
                "INVALID_SHA256", "expected a 64-character hexadecimal SHA256", field
            )
        ]
    return []


def _validate_fixed_identity(value: object, field: str) -> list[dict[str, str]]:
    if not isinstance(value, str) or not value or value.strip() != value:
        return [_error("INVALID_IDENTITY", "must be a non-empty trimmed string", field)]
    if value.lower() in FLOATING_IDENTITIES:
        return [
            _error("FLOATING_IDENTITY", "must use a fixed, immutable identity", field)
        ]
    return []


def _validate_file_declaration(
    declaration: object,
    field: str,
    manifest_dir: Path,
) -> tuple[dict[str, Any], list[dict[str, str]]]:
    errors: list[dict[str, str]] = []
    if not isinstance(declaration, dict):
        return {}, [_error("INVALID_FILE", "file declaration must be an object", field)]

    errors.extend(_unknown_keys(declaration, FILE_KEYS, field))
    kind = declaration.get("kind")
    if kind not in ALLOWED_FILE_KINDS:
        errors.append(
            _error(
                "INVALID_FILE_KIND",
                "must be model, tokenizer, or fixture",
                f"{field}.kind",
            )
        )

    raw_path = declaration.get("path")
    if not isinstance(raw_path, str) or not raw_path.strip():
        errors.append(
            _error(
                "INVALID_PATH", "must be a non-empty local file path", f"{field}.path"
            )
        )
        resolved_path = None
    elif "://" in raw_path:
        errors.append(
            _error(
                "REMOTE_PATH",
                "remote URLs are not allowed; provisioning is external",
                f"{field}.path",
            )
        )
        resolved_path = None
    else:
        path = Path(raw_path).expanduser()
        resolved_path = path if path.is_absolute() else manifest_dir / path

    errors.extend(_validate_sha256(declaration.get("sha256"), f"{field}.sha256"))
    result = {
        "kind": kind,
        "declared_path": raw_path,
        "path": str(resolved_path) if resolved_path is not None else None,
        "expected_sha256": declaration.get("sha256"),
    }
    return result, errors


def _validate_license(value: object, field: str) -> list[dict[str, str]]:
    errors: list[dict[str, str]] = []
    if not isinstance(value, dict):
        return [_error("INVALID_LICENSE", "license metadata must be an object", field)]
    errors.extend(_unknown_keys(value, LICENSE_KEYS, field))

    status = value.get("status")
    if status not in ALLOWED_LICENSE_STATUSES:
        errors.append(
            _error(
                "INVALID_LICENSE_STATUS",
                "must be approved, pending, rejected, or unknown",
                f"{field}.status",
            )
        )
    elif status != "approved":
        errors.append(
            _error(
                "LICENSE_NOT_APPROVED",
                "asset license must be approved",
                f"{field}.status",
            )
        )

    for key in ("redistributable", "third_party_llm_allowed"):
        if not isinstance(value.get(key), bool):
            errors.append(
                _error("INVALID_LICENSE_FLAG", "must be a boolean", f"{field}.{key}")
            )

    if not isinstance(value.get("spdx_id"), str) or not value["spdx_id"].strip():
        errors.append(
            _error(
                "MISSING_LICENSE_ID",
                "spdx_id or an explicit license identifier is required",
                f"{field}.spdx_id",
            )
        )
    if not isinstance(value.get("source_url"), str) or not value["source_url"].strip():
        errors.append(
            _error(
                "MISSING_LICENSE_SOURCE",
                "the reviewed license source is required",
                f"{field}.source_url",
            )
        )
    return errors


def _validate_usage(value: object, field: str, backend: object) -> list[dict[str, str]]:
    errors: list[dict[str, str]] = []
    if not isinstance(value, dict):
        return [_error("INVALID_USAGE", "usage metadata must be an object", field)]
    errors.extend(_unknown_keys(value, USAGE_KEYS, field))

    production = value.get("production")
    if not isinstance(production, bool):
        errors.append(
            _error(
                "INVALID_PRODUCTION_FLAG", "must be a boolean", f"{field}.production"
            )
        )

    environments = value.get("allowed_environments")
    if (
        not isinstance(environments, list)
        or not environments
        or not all(isinstance(item, str) and item.strip() for item in environments)
    ):
        errors.append(
            _error(
                "INVALID_ENVIRONMENTS",
                "must be a non-empty list of strings",
                f"{field}.allowed_environments",
            )
        )
        environments = []

    restrictions = value.get("restrictions")
    if not isinstance(restrictions, list) or not all(
        isinstance(item, str) and item.strip() for item in restrictions
    ):
        errors.append(
            _error(
                "INVALID_RESTRICTIONS",
                "must be a list of strings",
                f"{field}.restrictions",
            )
        )
        restrictions = []

    if production is True and "production" not in environments:
        errors.append(
            _error(
                "PRODUCTION_NOT_ALLOWED",
                "production assets must allow the production environment",
                f"{field}.allowed_environments",
            )
        )
    if backend == "deterministic-test":
        if production is not False:
            errors.append(
                _error(
                    "DETERMINISTIC_PRODUCTION",
                    "deterministic-test assets must be marked non-production",
                    f"{field}.production",
                )
            )
        if "production" in environments:
            errors.append(
                _error(
                    "DETERMINISTIC_PRODUCTION",
                    "deterministic-test assets cannot allow production",
                    f"{field}.allowed_environments",
                )
            )
        if "non-production-only" not in restrictions:
            errors.append(
                _error(
                    "MISSING_TEST_RESTRICTION",
                    "deterministic-test assets require non-production-only",
                    f"{field}.restrictions",
                )
            )
    return errors


def _validate_metadata(
    value: object, field: str, kind: object, backend: object
) -> list[dict[str, str]]:
    errors: list[dict[str, str]] = []
    if not isinstance(value, dict):
        return [_error("INVALID_METADATA", "model metadata must be an object", field)]
    errors.extend(_unknown_keys(value, METADATA_KEYS, field))

    if kind == "embedding":
        dimension = value.get("dimension")
        if not _is_non_bool_int(dimension) or dimension < 1:
            errors.append(
                _error(
                    "INVALID_DIMENSION",
                    "embedding dimension must be a positive integer",
                    f"{field}.dimension",
                )
            )
    else:
        output_dimension = value.get("output_dimension")
        if not _is_non_bool_int(output_dimension) or output_dimension < 1:
            errors.append(
                _error(
                    "INVALID_OUTPUT_DIMENSION",
                    "output dimension must be a positive integer",
                    f"{field}.output_dimension",
                )
            )

    if backend == "onnx-int8":
        max_length = value.get("max_length")
        if not _is_non_bool_int(max_length) or not 1 <= max_length <= 1024:
            errors.append(
                _error(
                    "INVALID_MAX_LENGTH",
                    "ONNX max_length must be an integer from 1 through 1024",
                    f"{field}.max_length",
                )
            )
        if value.get("quantization") != "int8":
            errors.append(
                _error(
                    "INVALID_QUANTIZATION",
                    "onnx-int8 assets must declare int8 quantization",
                    f"{field}.quantization",
                )
            )
    return errors


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _verify_file(
    file_info: dict[str, Any],
) -> tuple[dict[str, Any], list[dict[str, str]]]:
    errors: list[dict[str, str]] = []
    path_value = file_info.get("path")
    path = Path(path_value) if isinstance(path_value, str) else None
    result: dict[str, Any] = {
        "kind": file_info.get("kind"),
        "path": path_value,
        "expected_sha256": file_info.get("expected_sha256"),
        "exists": False,
        "status": "FAIL",
    }
    if path is None or not path.is_file():
        errors.append(
            _error("MISSING_FILE", "declared asset file does not exist", "path")
        )
        return result, errors

    result["exists"] = True
    result["bytes"] = path.stat().st_size
    actual_sha256 = _hash_file(path)
    result["sha256"] = actual_sha256
    if actual_sha256.lower() != str(file_info["expected_sha256"]).lower():
        errors.append(
            _error(
                "SHA256_MISMATCH",
                "file content does not match the manifest SHA256",
                "sha256",
            )
        )
    else:
        result["status"] = "PASS"
    return result, errors


def _asset_result(
    asset: object,
    index: int,
    manifest_dir: Path,
    seen_ids: set[str],
    seen_identities: set[tuple[object, object, object]],
) -> dict[str, Any]:
    field = f"assets[{index}]"
    errors: list[dict[str, str]] = []
    result: dict[str, Any] = {
        "index": index,
        "status": "FAIL",
        "errors": [],
        "files": [],
    }
    if not isinstance(asset, dict):
        result["errors"] = [
            _error("INVALID_ASSET", "asset declaration must be an object", field)
        ]
        return result

    errors.extend(_unknown_keys(asset, ASSET_KEYS, field))
    asset_id = asset.get("id")
    result["id"] = asset_id
    if not isinstance(asset_id, str) or not asset_id.strip():
        errors.append(
            _error("INVALID_ASSET_ID", "must be a non-empty string", f"{field}.id")
        )
    elif asset_id in seen_ids:
        errors.append(
            _error("DUPLICATE_ASSET_ID", "asset id must be unique", f"{field}.id")
        )
    else:
        seen_ids.add(asset_id)

    kind = asset.get("kind")
    if kind not in ALLOWED_KINDS:
        errors.append(
            _error(
                "INVALID_ASSET_KIND", "must be embedding or reranker", f"{field}.kind"
            )
        )
    model_name = asset.get("model_name")
    errors.extend(_validate_fixed_identity(model_name, f"{field}.model_name"))
    version = asset.get("version")
    errors.extend(_validate_fixed_identity(version, f"{field}.version"))
    backend = asset.get("backend")
    if backend not in ALLOWED_BACKENDS:
        errors.append(
            _error(
                "INVALID_BACKEND",
                "must be onnx-int8 or deterministic-test",
                f"{field}.backend",
            )
        )

    identity = (kind, model_name, version)
    if identity in seen_identities:
        errors.append(
            _error(
                "DUPLICATE_MODEL_IDENTITY",
                "kind, model_name, and version must be unique",
                field,
            )
        )
    else:
        seen_identities.add(identity)

    files = asset.get("files")
    parsed_files: list[dict[str, Any]] = []
    if not isinstance(files, list) or not files:
        errors.append(
            _error(
                "MISSING_FILES", "at least one asset file is required", f"{field}.files"
            )
        )
        files = []
    for file_index, declaration in enumerate(files):
        parsed, file_errors = _validate_file_declaration(
            declaration, f"{field}.files[{file_index}]", manifest_dir
        )
        parsed_files.append(parsed)
        errors.extend(file_errors)
    file_kinds = [item.get("kind") for item in parsed_files]
    if backend == "onnx-int8" and file_kinds.count("model") != 1:
        errors.append(
            _error(
                "MISSING_MODEL_FILE",
                "onnx-int8 assets require exactly one model file",
                f"{field}.files",
            )
        )
    if backend == "onnx-int8" and file_kinds.count("tokenizer") != 1:
        errors.append(
            _error(
                "MISSING_TOKENIZER_FILE",
                "onnx-int8 assets require exactly one tokenizer file",
                f"{field}.files",
            )
        )
    if backend == "deterministic-test" and not ({"model", "fixture"} & set(file_kinds)):
        errors.append(
            _error(
                "MISSING_TEST_ASSET",
                "deterministic-test assets require a model or fixture file",
                f"{field}.files",
            )
        )

    errors.extend(
        _validate_metadata(asset.get("metadata"), f"{field}.metadata", kind, backend)
    )
    errors.extend(_validate_license(asset.get("license"), f"{field}.license"))
    errors.extend(_validate_usage(asset.get("usage"), f"{field}.usage", backend))

    purposes = asset.get("purposes")
    if (
        not isinstance(purposes, list)
        or not purposes
        or not all(isinstance(item, str) and item.strip() for item in purposes)
    ):
        errors.append(
            _error(
                "INVALID_PURPOSES",
                "must be a non-empty list of strings",
                f"{field}.purposes",
            )
        )

    file_results: list[dict[str, Any]] = []
    if not errors:
        for file_info in parsed_files:
            file_result, file_errors = _verify_file(file_info)
            file_results.append(file_result)
            errors.extend(file_errors)
    result.update(
        {
            "model_name": model_name,
            "version": version,
            "backend": backend,
            "kind": kind,
            "production": asset.get("usage", {}).get("production")
            if isinstance(asset.get("usage"), dict)
            else None,
            "files": file_results,
            "errors": errors,
            "status": "PASS" if not errors else "FAIL",
        }
    )
    return result


def _failure_report(manifest: str, errors: list[dict[str, str]]) -> dict[str, Any]:
    return {
        "tool_version": TOOL_VERSION,
        "verification_mode": "verify-only",
        "network_accessed": False,
        "downloads_attempted": False,
        "manifest": manifest,
        "status": "FAIL",
        "assets": [],
        "summary": {
            "asset_count": 0,
            "passed": 0,
            "failed": 0,
            "error_count": len(errors),
        },
        "errors": errors,
    }


def verify_manifest(manifest_path: Path) -> dict[str, Any]:
    """Return a JSON-serializable verification report for one local manifest."""
    manifest_string = str(manifest_path)
    try:
        with manifest_path.open("r", encoding="utf-8") as stream:
            document = json.load(stream)
    except FileNotFoundError:
        return _failure_report(
            manifest_string,
            [_error("MANIFEST_NOT_FOUND", "manifest file does not exist")],
        )
    except (OSError, UnicodeDecodeError) as exc:
        return _failure_report(
            manifest_string, [_error("MANIFEST_READ_ERROR", str(exc))]
        )
    except json.JSONDecodeError as exc:
        return _failure_report(manifest_string, [_error("INVALID_JSON", str(exc))])

    if not isinstance(document, dict):
        return _failure_report(
            manifest_string,
            [_error("INVALID_MANIFEST", "manifest root must be an object")],
        )
    errors = _unknown_keys(document, ROOT_KEYS, "manifest")
    if document.get("schema_version") != SCHEMA_VERSION:
        errors.append(
            _error(
                "UNSUPPORTED_SCHEMA",
                f"schema_version must be {SCHEMA_VERSION}",
                "schema_version",
            )
        )
    assets = document.get("assets")
    if not isinstance(assets, list) or not assets:
        errors.append(
            _error(
                "MISSING_ASSETS",
                "manifest must contain a non-empty assets list",
                "assets",
            )
        )
        assets = []

    seen_ids: set[str] = set()
    seen_identities: set[tuple[object, object, object]] = set()
    asset_reports = [
        _asset_result(asset, index, manifest_path.parent, seen_ids, seen_identities)
        for index, asset in enumerate(assets)
    ]
    failed_asset_count = sum(report["status"] == "FAIL" for report in asset_reports)
    error_count = len(errors) + sum(len(report["errors"]) for report in asset_reports)
    status = "PASS" if not errors and failed_asset_count == 0 else "FAIL"
    return {
        "tool_version": TOOL_VERSION,
        "verification_mode": "verify-only",
        "network_accessed": False,
        "downloads_attempted": False,
        "manifest": manifest_string,
        "status": status,
        "assets": asset_reports,
        "summary": {
            "asset_count": len(asset_reports),
            "passed": len(asset_reports) - failed_asset_count,
            "failed": failed_asset_count,
            "error_count": error_count,
        },
        "errors": errors,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify model asset manifests without downloading weights."
    )
    parser.add_argument(
        "--manifest",
        required=True,
        type=Path,
        help="Path to the model asset manifest JSON file.",
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="Indent the JSON report for human inspection.",
    )
    args = parser.parse_args(argv)

    report = verify_manifest(args.manifest.expanduser().resolve())
    print(
        json.dumps(
            report, ensure_ascii=True, indent=2 if args.pretty else None, sort_keys=True
        )
    )
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
