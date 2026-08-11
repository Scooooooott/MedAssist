from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from dataclasses import replace
from pathlib import Path
from urllib.error import URLError
from unittest.mock import patch

import fetch_data


class FakeResponse:
    def __init__(self, payload: bytes, *, status: int = 200, content_type: str = "text/plain") -> None:
        self.payload = io.BytesIO(payload)
        self.status = status
        self.headers = {"Content-Type": content_type}

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def read(self, size: int = -1) -> bytes:
        return self.payload.read(size)


class FetchDataTests(unittest.TestCase):
    def test_source_manifest_is_stable_and_complete(self) -> None:
        first = fetch_data.build_source_manifest()
        second = fetch_data.build_source_manifest()

        self.assertEqual(first, second)
        self.assertNotIn("created_at", first)
        self.assertEqual(
            [item["source_id"] for item in first["sources"]],
            ["ahrq", "cdc", "dailymed", "mtsamples", "pmc-patients", "synthea", "uspstf"],
        )
        synthea = next(item for item in first["sources"] if item["source_id"] == "synthea")
        self.assertEqual(synthea["version"], fetch_data.SYNTHEA_DEFAULT_VERSION)
        self.assertEqual(synthea["policy"]["retries"], 3)
        self.assertEqual(first["generators"]["synthea"]["patient_count"], 1000)
        self.assertEqual(first["generators"]["synthea"]["seed"], fetch_data.SYNTHEA_DEFAULT_SEED)

    def test_file_manifest_is_sorted_and_hash_validation_is_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "note.txt"
            path.write_text("safe synthetic note", encoding="utf-8")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            validated = fetch_data.validate_raw_file(path, expected_sha256=digest)
            record = fetch_data.FileRecord(
                source_id="synthea",
                relative_path="synthea/note.txt",
                size_bytes=validated.size_bytes,
                sha256=validated.sha256,
                status=validated.status,
                source_url="https://example.test",
                source_revision="test",
            )
            manifest = fetch_data.build_file_manifest([record])

        self.assertEqual(manifest["files"][0]["sha256"], digest)
        self.assertEqual(manifest["schema_version"], fetch_data.MANIFEST_SCHEMA_VERSION)

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "note.txt"
            path.write_text("changed", encoding="utf-8")
            with self.assertRaises(fetch_data.ValidationError):
                fetch_data.validate_raw_file(path, expected_sha256=digest)

    def test_normalize_files_writes_unified_jsonl_without_reordering_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw_dir = root / "synthea"
            raw_dir.mkdir()
            raw_file = raw_dir / "record.txt"
            raw_file.write_text("synthetic content", encoding="utf-8")
            result = fetch_data.FetchResult(
                source_id="synthea",
                output_dir=raw_dir,
                files=(raw_file,),
                retrieved_at="2026-08-10T00:00:00+00:00",
                source_revision="test-revision",
            )
            output = root / "normalized" / "synthea.jsonl"
            documents = fetch_data.normalize_files(
                fetch_data.source_specs()["synthea"],
                result,
                output_path=output,
            )
            row = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(len(documents), 1)
        self.assertEqual(row["source_id"], "synthea")
        self.assertEqual(row["text"], "synthetic content")
        self.assertEqual(row["raw_relative_path"], "synthea/record.txt")
        self.assertEqual(row["normalization_status"], "NORMALIZED_TEXT")
        self.assertEqual(row["retrieved_at"], "2026-08-10T00:00:00+00:00")

    def test_normalize_mtsamples_csv_emits_one_document_per_row_with_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw_dir = root / "mtsamples"
            raw_dir.mkdir()
            raw_file = raw_dir / "notes.csv"
            raw_file.write_text(
                "Unnamed: 0,medical_specialty,sample_name,transcription,keywords\n"
                '1,Cardiology,Consult note,"Patient has chest pain.",cardiac\n'
                '2,Neurology,Follow-up note,"No new weakness.",neuro\n',
                encoding="utf-8",
            )
            result = fetch_data.FetchResult(
                source_id="mtsamples",
                output_dir=raw_dir,
                files=(raw_file,),
                retrieved_at="2026-08-10T00:00:00+00:00",
                source_revision="mtsamples-test",
                source_urls=("https://example.test/mtsamples.csv",),
            )
            output = root / "normalized" / "mtsamples.jsonl"
            documents = fetch_data.normalize_files(
                fetch_data.source_specs()["mtsamples"],
                result,
                output_path=output,
            )
            rows = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]

        self.assertEqual(len(documents), 2)
        self.assertEqual([row["text"] for row in rows], ["Patient has chest pain.", "No new weakness."])
        self.assertEqual(rows[0]["title"], "Consult note")
        self.assertEqual(rows[0]["metadata"]["specialty"], "Cardiology")
        self.assertEqual(rows[0]["metadata"]["row_number"], 2)
        self.assertEqual(rows[1]["metadata"]["record_identifier"], "2")
        self.assertEqual(rows[0]["source_url"], "https://example.test/mtsamples.csv")
        self.assertNotEqual(rows[0]["document_id"], rows[1]["document_id"])
        self.assertNotEqual(rows[0]["content_hash"], rows[1]["content_hash"])

    def test_normalize_pmc_json_and_jsonl_preserves_pmid_and_uid(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw_dir = root / "pmc-patients"
            raw_dir.mkdir()
            json_file = raw_dir / "patients.json"
            json_file.write_text(
                json.dumps(
                    [
                        {"PMID": "123", "patient_uid": "p-1", "title": "Case one", "patient_summary": "Summary one"},
                        {"PMID": "456", "patient_uid": "p-2", "title": "Case two", "patient_summary": "Summary two"},
                    ]
                ),
                encoding="utf-8",
            )
            jsonl_file = raw_dir / "patients.jsonl"
            jsonl_file.write_text(
                '{"PMID":"789","patient_uid":"p-3","title":"Case three","patient_summary":"Summary three"}\n',
                encoding="utf-8",
            )
            spec = fetch_data.source_specs()["pmc-patients"]

            json_documents = fetch_data.normalize_files(
                spec,
                fetch_data.FetchResult(
                    "pmc-patients", raw_dir, (json_file,), "2026-08-10T00:00:00+00:00", "pmc-test"
                ),
                output_path=root / "normalized" / "patients.jsonl",
            )
            jsonl_documents = fetch_data.normalize_files(
                spec,
                fetch_data.FetchResult(
                    "pmc-patients", raw_dir, (jsonl_file,), "2026-08-10T00:00:00+00:00", "pmc-test"
                ),
                output_path=root / "normalized" / "patients-jsonl.jsonl",
            )

        self.assertEqual(len(json_documents), 2)
        self.assertEqual(json_documents[0].metadata["pmid"], "123")
        self.assertEqual(json_documents[0].metadata["uid"], "p-1")
        self.assertEqual(len(jsonl_documents), 1)
        self.assertEqual(jsonl_documents[0].metadata["line_number"], 1)
        self.assertEqual(jsonl_documents[0].text, "Summary three")

    def test_normalize_bad_structured_record_fails_closed_without_replacing_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw_dir = root / "pmc-patients"
            raw_dir.mkdir()
            raw_file = raw_dir / "patients.jsonl"
            raw_file.write_text(
                '{"PMID":"123","patient_uid":"p-1","patient_summary":"Valid"}\n'
                '{"PMID":"456","patient_uid":"p-2","patient_summary":}\n',
                encoding="utf-8",
            )
            output = root / "normalized" / "patients.jsonl"
            output.parent.mkdir()
            output.write_text("previous successful output\n", encoding="utf-8")
            result = fetch_data.FetchResult("pmc-patients", raw_dir, (raw_file,), None, "pmc-test")

            with self.assertRaisesRegex(fetch_data.ValidationError, r"line 2.*invalid JSON"):
                fetch_data.normalize_files(fetch_data.source_specs()["pmc-patients"], result, output_path=output)

            self.assertEqual(output.read_text(encoding="utf-8"), "previous successful output\n")
            self.assertFalse(output.with_name(output.name + ".part").exists())

    def test_synthea_validation_requires_fhir_bundles_and_population_match(self) -> None:
        def bundle(patient_id: str) -> str:
            return json.dumps(
                {
                    "resourceType": "Bundle",
                    "type": "transaction",
                    "entry": [
                        {"resource": {"resourceType": "Patient", "id": patient_id}},
                        {"resource": {"resourceType": "Observation", "id": f"obs-{patient_id}"}},
                    ],
                }
            )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output_dir = root / "synthea"
            output_dir.mkdir()
            first = output_dir / "patient-1.json"
            second = output_dir / "patient-2.json"
            first.write_text(bundle("p-1"), encoding="utf-8")
            second.write_text(bundle("p-2"), encoding="utf-8")
            files = (first, second)

            report = fetch_data.validate_synthea_output(output_dir, files, patient_count=2)
            resumed = fetch_data.SyntheaFetcher().fetch(
                fetch_data.source_specs()["synthea"],
                fetch_data.FetchContext(
                    output_dir=root,
                    resume=True,
                    synthea=fetch_data.SyntheaConfig(patient_count=2),
                ),
            )

            second.write_text(bundle("p-1"), encoding="utf-8")
            with self.assertRaisesRegex(fetch_data.ValidationError, "population mismatch|duplicate Patient"):
                fetch_data.validate_synthea_output(output_dir, files, patient_count=2)

        self.assertEqual(report.status, "LOCAL_INVARIANTS_PASSED")
        self.assertEqual(report.external_schema_validation, "UNVERIFIED")
        self.assertEqual(report.bundle_count, 2)
        self.assertEqual(report.resource_count, 4)
        self.assertEqual(resumed.validation, report)

    def test_license_and_third_party_llm_gates_fail_closed(self) -> None:
        specs = fetch_data.source_specs()
        with self.assertRaisesRegex(fetch_data.GateError, "not approved"):
            fetch_data.enforce_source_gate(specs["mtsamples"], environ={})
        with self.assertRaisesRegex(fetch_data.GateError, "third-party LLM"):
            fetch_data.enforce_third_party_llm_gate(specs["pmc-patients"])
        fetch_data.enforce_source_gate(specs["pmc-patients"], environ={})

    def test_robots_policy_and_rate_limit_are_enforced_without_network(self) -> None:
        denied = fetch_data.robots_allowed(
            "https://example.test/private/file.txt",
            user_agent="MedAssist-test",
            robots_reader=lambda _url: "User-agent: *\nDisallow: /private/\n",
        )
        allowed = fetch_data.robots_allowed(
            "https://example.test/public/file.txt",
            user_agent="MedAssist-test",
            robots_reader=lambda _url: "User-agent: *\nDisallow: /private/\n",
        )
        self.assertFalse(denied)
        self.assertTrue(allowed)

        now = [0.0]
        sleeps: list[float] = []

        def clock() -> float:
            return now[0]

        def sleeper(seconds: float) -> None:
            sleeps.append(seconds)
            now[0] += seconds

        limiter = fetch_data.RateLimiter(1.0, clock=clock, sleeper=sleeper)
        limiter.wait("example.test")
        limiter.wait("example.test")
        self.assertEqual(sleeps, [1.0])

    def test_download_retries_and_verifies_sha256_without_network(self) -> None:
        payload = b"downloaded synthetic fixture"
        calls = [0]

        def opener(_request: object, **_kwargs: object) -> FakeResponse:
            calls[0] += 1
            if calls[0] == 1:
                raise URLError("temporary test failure")
            return FakeResponse(payload)

        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "fixture.txt"
            policy = fetch_data.FetchPolicy(rate_limit_seconds=0, retries=1, backoff_seconds=0)
            with patch.object(fetch_data.time, "sleep"):
                record = fetch_data.download_file(
                    "https://example.test/fixture.txt",
                    destination,
                    policy=policy,
                    expected_sha256=hashlib.sha256(payload).hexdigest(),
                    opener=opener,
                    robot_checker=lambda _url, _agent: True,
                )
            self.assertEqual(destination.read_bytes(), payload)
            self.assertEqual(record.sha256, hashlib.sha256(payload).hexdigest())
            self.assertEqual(calls[0], 2)

    def test_download_resume_uses_partial_file_and_range_response(self) -> None:
        payload = b"hello world"
        requests: list[object] = []

        def opener(request: object, **_kwargs: object) -> FakeResponse:
            requests.append(request)
            return FakeResponse(b" world", status=206)

        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "fixture.txt"
            destination.with_name("fixture.txt.part").write_bytes(b"hello")
            policy = fetch_data.FetchPolicy(rate_limit_seconds=0, retries=0)
            record = fetch_data.download_file(
                "https://example.test/fixture.txt",
                destination,
                policy=policy,
                expected_sha256=hashlib.sha256(payload).hexdigest(),
                resume=True,
                opener=opener,
                robot_checker=lambda _url, _agent: True,
            )
            self.assertEqual(destination.read_bytes(), payload)
            self.assertEqual(record.size_bytes, len(payload))
            self.assertIn("bytes=5-", requests[0].headers["Range"])

    def test_source_fetchers_are_registered_and_guidance_requires_allowlist(self) -> None:
        self.assertEqual(set(fetch_data.FETCHERS), set(fetch_data.source_specs()))
        with tempfile.TemporaryDirectory() as directory:
            spec = fetch_data.source_specs()["cdc"]
            context = fetch_data.FetchContext(output_dir=Path(directory))
            plan = fetch_data.FETCHERS["cdc"].plan(spec, context)
            self.assertRegex(plan.blocked_reason or "", "requires an allowlist")
            with self.assertRaisesRegex(fetch_data.ConfigurationError, "requires an allowlist"):
                fetch_data.FETCHERS["cdc"].fetch(spec, context)

    def test_guidance_fetcher_downloads_only_json_allowlist_urls(self) -> None:
        calls: list[str] = []

        def fake_download(url: str, destination: Path, **_kwargs: object) -> object:
            calls.append(url)
            destination.write_text(f"guidance from {url}", encoding="utf-8")
            return fetch_data.validate_raw_file(destination)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            allowlist = root / "cdc.json"
            allowlist.write_text(json.dumps({"urls": [
                "https://www.cdc.gov/page-a.html",
                "https://www.cdc.gov/page-b.html",
            ]}), encoding="utf-8")
            context = fetch_data.FetchContext(
                output_dir=root / "data",
                environ={"MEDASSIST_CDC_ALLOWLIST": str(allowlist)},
            )
            with patch.object(fetch_data, "download_file", side_effect=fake_download):
                result = fetch_data.FETCHERS["cdc"].fetch(fetch_data.source_specs()["cdc"], context)
            normalized = fetch_data.normalize_files(
                fetch_data.source_specs()["cdc"],
                result,
                output_path=root / "normalized.jsonl",
            )

        self.assertEqual(calls, [
            "https://www.cdc.gov/page-a.html",
            "https://www.cdc.gov/page-b.html",
        ])
        self.assertEqual(len(result.files), 2)
        self.assertTrue(all(path.parent == result.output_dir for path in result.files))
        self.assertEqual(result.source_urls, tuple(calls))
        self.assertEqual([document.source_url for document in normalized], calls)

    def test_guidance_allowlist_rejects_non_http_targets(self) -> None:
        with self.assertRaisesRegex(fetch_data.ConfigurationError, "non-http URL"):
            fetch_data._allowlisted_urls("cdc", {"MEDASSIST_CDC_ALLOWLIST": '["file:///secret"]'})

    def test_pmc_requires_revision_and_explicit_bounded_input(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            context = fetch_data.FetchContext(output_dir=Path(directory), environ={})
            with self.assertRaisesRegex(fetch_data.ConfigurationError, "requires .*REVISION"):
                fetch_data.FETCHERS["pmc-patients"].fetch(fetch_data.source_specs()["pmc-patients"], context)

    def test_pmc_uses_local_file_and_limits_records_without_network(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            local = root / "patients.jsonl"
            local.write_text('{"id": 1}\n{"id": 2}\n{"id": 3}\n', encoding="utf-8")
            context = fetch_data.FetchContext(
                output_dir=root / "data",
                environ={
                    "MEDASSIST_PMC_PATIENTS_LOCAL_FILE": str(local),
                    "MEDASSIST_PMC_PATIENTS_REVISION": "abc123",
                    "MEDASSIST_PMC_PATIENTS_SAMPLE": "2",
                },
            )
            with patch.object(fetch_data, "download_file") as downloader:
                result = fetch_data.FETCHERS["pmc-patients"].fetch(fetch_data.source_specs()["pmc-patients"], context)
            self.assertFalse(downloader.called)
            self.assertEqual(result.source_revision, "abc123")
            self.assertEqual(len(result.files[0].read_text(encoding="utf-8").splitlines()), 2)

    def test_pmc_rejects_sample_above_hard_limit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            context = fetch_data.FetchContext(
                output_dir=Path(directory),
                environ={
                    "MEDASSIST_PMC_PATIENTS_DATASET_URL": "https://huggingface.co/file.jsonl",
                    "MEDASSIST_PMC_PATIENTS_REVISION": "main",
                    "MEDASSIST_PMC_PATIENTS_SAMPLE": "20001",
                },
            )
            with self.assertRaisesRegex(fetch_data.ConfigurationError, "between 1 and 20000"):
                fetch_data.FETCHERS["pmc-patients"].fetch(fetch_data.source_specs()["pmc-patients"], context)

    def test_mtsamples_preserves_license_and_credential_gates(self) -> None:
        spec = fetch_data.source_specs()["mtsamples"]
        with tempfile.TemporaryDirectory() as directory:
            context = fetch_data.FetchContext(
                output_dir=Path(directory),
                environ={"MEDASSIST_MTSAMPLES_FILE_URL": "https://example.test/mtsamples.zip", "KAGGLE_API_TOKEN": "token"},
            )
            with self.assertRaises(fetch_data.GateError):
                fetch_data.FETCHERS["mtsamples"].fetch(spec, context)

        approved = replace(spec, license=replace(spec.license, status="APPROVED"))
        with tempfile.TemporaryDirectory() as directory:
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, "w") as handle:
                handle.writestr("notes.csv", "id,text\n1,example\n")

            def fake_download(_url: str, destination: Path, **_kwargs: object) -> object:
                destination.write_bytes(archive.getvalue())
                return fetch_data.validate_raw_file(destination)

            context = fetch_data.FetchContext(
                output_dir=Path(directory),
                environ={"MEDASSIST_MTSAMPLES_FILE_URL": "https://example.test/mtsamples.zip", "KAGGLE_API_TOKEN": "token"},
            )
            with patch.object(fetch_data, "download_file", side_effect=fake_download):
                result = fetch_data.FETCHERS["mtsamples"].fetch(approved, context)
            self.assertEqual([path.name for path in result.files], ["notes.csv"])

        with tempfile.TemporaryDirectory() as directory:
            context = fetch_data.FetchContext(
                output_dir=Path(directory),
                environ={"MEDASSIST_MTSAMPLES_FILE_URL": "https://example.test/mtsamples.csv"},
            )
            with self.assertRaisesRegex(fetch_data.ConfigurationError, "requires credentials"):
                fetch_data.FETCHERS["mtsamples"].fetch(approved, context)

    def test_synthea_requires_explicit_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            spec = fetch_data.source_specs()["synthea"]
            context = fetch_data.FetchContext(output_dir=Path(directory))
            plan = fetch_data.FETCHERS["synthea"].plan(spec, context)
            self.assertIsNotNone(plan.blocked_reason)
            with self.assertRaisesRegex(fetch_data.ConfigurationError, "command is not configured"):
                fetch_data.FETCHERS["synthea"].fetch(spec, context)

    def test_cli_manifest_only_and_dry_run_do_not_download(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory) / "data"
            self.assertEqual(fetch_data.main(["--manifest-only", "--output-dir", str(output_dir)]), 0)
            self.assertTrue((output_dir / "source-manifest.json").is_file())
            self.assertTrue((output_dir / "file-manifest.json").is_file())
            self.assertEqual(json.loads((output_dir / "file-manifest.json").read_text())["files"], [])

            dry_run_dir = Path(directory) / "dry-run"
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                result = fetch_data.main(["--dry-run", "--source", "pmc-patients", "--output-dir", str(dry_run_dir)])
            self.assertEqual(result, 0)
            self.assertIn("pmc-patients", stdout.getvalue())
            self.assertFalse(dry_run_dir.exists())


if __name__ == "__main__":
    unittest.main()
