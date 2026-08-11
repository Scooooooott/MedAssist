from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify_model_assets.py")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _license() -> dict[str, object]:
    return {
        "status": "approved",
        "spdx_id": "Apache-2.0",
        "source_url": "https://example.invalid/reviewed-license",
        "reviewed_at": "2026-08-10",
        "redistributable": False,
        "third_party_llm_allowed": False,
    }


def _usage(production: bool = True) -> dict[str, object]:
    return {
        "production": production,
        "allowed_environments": ["production"] if production else ["test"],
        "restrictions": [] if production else ["non-production-only"],
    }


def _manifest(
    model: Path, tokenizer: Path, backend: str = "onnx-int8"
) -> dict[str, object]:
    production = backend == "onnx-int8"
    files = [
        {
            "kind": "model" if production else "fixture",
            "path": str(model),
            "sha256": _sha256(model),
        },
    ]
    if production:
        files.append(
            {"kind": "tokenizer", "path": str(tokenizer), "sha256": _sha256(tokenizer)}
        )
    return {
        "schema_version": 1,
        "assets": [
            {
                "id": "test-asset",
                "kind": "embedding",
                "model_name": "test/model",
                "version": "test-v1",
                "backend": backend,
                "files": files,
                "metadata": {"dimension": 3, "max_length": 16, "quantization": "int8"}
                if production
                else {"dimension": 3},
                "license": _license(),
                "usage": _usage(production),
                "purposes": ["unit-test"],
            }
        ],
    }


class VerifyModelAssetsTests(unittest.TestCase):
    def test_valid_production_manifest_checks_local_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "model.onnx"
            tokenizer = root / "tokenizer.json"
            model.write_bytes(b"not-a-real-model-for-a-unit-test")
            tokenizer.write_bytes(b"not-a-real-tokenizer-for-a-unit-test")
            manifest_path = root / "manifest.json"
            manifest_path.write_text(
                json.dumps(_manifest(model, tokenizer)), encoding="utf-8"
            )

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--manifest", str(manifest_path)],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report["status"], "PASS")
            self.assertEqual(report["summary"]["passed"], 1)
            self.assertFalse(report["downloads_attempted"])
            self.assertFalse(report["network_accessed"])

    def test_missing_file_is_a_machine_readable_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "missing.onnx"
            tokenizer = root / "tokenizer.json"
            model.write_bytes(b"model that will be removed")
            tokenizer.write_bytes(b"tokenizer")
            manifest = _manifest(model, tokenizer)
            model.unlink()
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--manifest", str(manifest_path)],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 1)
            report = json.loads(result.stdout)
            codes = {item["code"] for item in report["assets"][0]["errors"]}
            self.assertIn("MISSING_FILE", codes)

    def test_hash_mismatch_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "model.onnx"
            tokenizer = root / "tokenizer.json"
            model.write_bytes(b"model")
            tokenizer.write_bytes(b"tokenizer")
            manifest = _manifest(model, tokenizer)
            files = manifest["assets"][0]["files"]
            files[0]["sha256"] = "0" * 64

            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--manifest", str(manifest_path)],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 1)
            report = json.loads(result.stdout)
            codes = {item["code"] for item in report["assets"][0]["errors"]}
            self.assertIn("SHA256_MISMATCH", codes)

    def test_unapproved_license_and_invalid_metadata_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "model.onnx"
            tokenizer = root / "tokenizer.json"
            model.write_bytes(b"model")
            tokenizer.write_bytes(b"tokenizer")
            manifest = _manifest(model, tokenizer)
            asset = manifest["assets"][0]
            asset["version"] = "latest"
            asset["metadata"]["dimension"] = 0
            asset["license"]["status"] = "pending"
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--manifest", str(manifest_path)],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 1)
            report = json.loads(result.stdout)
            codes = {item["code"] for item in report["assets"][0]["errors"]}
            self.assertTrue(
                {"FLOATING_IDENTITY", "INVALID_DIMENSION", "LICENSE_NOT_APPROVED"}
                <= codes
            )

    def test_deterministic_test_asset_is_explicitly_non_production(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "deterministic.fixture"
            fixture.write_bytes(b"deterministic test fixture; not model weights")
            tokenizer = root / "unused-tokenizer"
            manifest_path = root / "manifest.json"
            manifest_path.write_text(
                json.dumps(_manifest(fixture, tokenizer, "deterministic-test")),
                encoding="utf-8",
            )

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--manifest", str(manifest_path)],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report["assets"][0]["backend"], "deterministic-test")
            self.assertFalse(report["assets"][0]["production"])

    def test_deterministic_test_asset_cannot_claim_production(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "deterministic.fixture"
            fixture.write_bytes(b"fixture")
            manifest = _manifest(
                fixture, root / "unused-tokenizer", "deterministic-test"
            )
            manifest["assets"][0]["usage"] = _usage(True)
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--manifest", str(manifest_path)],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 1)
            report = json.loads(result.stdout)
            codes = {item["code"] for item in report["assets"][0]["errors"]}
            self.assertIn("DETERMINISTIC_PRODUCTION", codes)

    def test_reranker_requires_output_dimension_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = root / "reranker.onnx"
            tokenizer = root / "tokenizer.json"
            model.write_bytes(b"reranker")
            tokenizer.write_bytes(b"tokenizer")
            manifest = _manifest(model, tokenizer)
            asset = manifest["assets"][0]
            asset["kind"] = "reranker"
            asset["metadata"] = {"max_length": 512, "quantization": "int8"}
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--manifest", str(manifest_path)],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 1)
            report = json.loads(result.stdout)
            codes = {item["code"] for item in report["assets"][0]["errors"]}
            self.assertIn("INVALID_OUTPUT_DIMENSION", codes)


if __name__ == "__main__":
    unittest.main()
