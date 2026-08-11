from __future__ import annotations

import copy
import json
import shutil
import tempfile
from pathlib import Path

import pytest

from policy_compiler import (
    GovernanceError,
    assert_no_drift,
    compile_outputs,
    compile_to_directory,
    drift_diff,
    load_schema_fixture,
    validate_manifest,
    validate_manifest_file,
)
from policy_compiler import dry_run_diff, render_permission_matrix


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "governance" / "policy-manifest.yaml"
SCHEMA = ROOT / "governance" / "policy-manifest.schema.json"
FIXTURE = Path(__file__).parent / "fixtures" / "m1-m3-schema.json"


@pytest.fixture()
def manifest() -> dict:
    return validate_manifest_file(MANIFEST, SCHEMA)


def test_golden_determinism_and_generated_headers(manifest: dict) -> None:
    first = compile_outputs(manifest, generated_at="2026-08-11T00:00:00Z")
    second = compile_outputs(copy.deepcopy(manifest), generated_at="2026-08-11T00:00:00Z")
    assert first == second
    assert first["sql/grants-and-rls.sql"].startswith("-- DO NOT EDIT:")
    for relative in first:
        if relative.endswith(".json"):
            body = json.loads(first[relative])
            assert body["_generated_header"].startswith("DO NOT EDIT:")
    metadata = json.loads(first["generation-metadata.json"])
    assert metadata["policy"]["generated_at"] == "2026-08-11T00:00:00Z"
    assert "2026-08-11" not in first["sql/grants-and-rls.sql"]


def test_view_relations_keep_grants_but_skip_rls_and_policies(manifest: dict) -> None:
    sql = compile_outputs(manifest)["sql/grants-and-rls.sql"]

    assert 'GRANT SELECT ("aggregate_count", "code", "code_system", "patient_count", "status") ON TABLE public."clinical_research_condition_counts" TO "medassist_clinician";' in sql
    assert 'GRANT SELECT ("aggregate_count", "code", "code_system", "patient_count", "status") ON TABLE public."clinical_research_condition_counts" TO "medassist_researcher";' in sql
    assert 'GRANT SELECT ("aggregate_count", "code", "code_system", "patient_count", "status") ON TABLE public."clinical_research_condition_counts" TO "medassist_admin";' in sql
    assert 'ALTER TABLE public."clinical_research_condition_counts" ENABLE ROW LEVEL SECURITY;' not in sql
    assert 'DROP POLICY IF EXISTS "policy_clinical_research_condition_counts_' not in sql
    assert 'CREATE POLICY "policy_clinical_research_condition_counts_' not in sql

    assert 'ALTER TABLE public."document" ENABLE ROW LEVEL SECURITY;' in sql
    assert 'CREATE POLICY "policy_document_clinician" ON public."document"' in sql


@pytest.fixture()
def local_tmp_dir() -> Path:
    root = Path(__file__).parent / ".test-tmp"
    root.mkdir(exist_ok=True)
    path = Path(tempfile.mkdtemp(dir=root))
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


def test_dry_run_rendering_does_not_write(local_tmp_dir: Path, manifest: dict) -> None:
    output_dir = local_tmp_dir / "generated"
    result = dry_run_diff(manifest, output_dir, generated_at="2026-08-11T00:00:00Z")
    assert result["missing"]
    assert result["changed"] == []
    assert not output_dir.exists()

    compile_to_directory(manifest, output_dir, generated_at="2026-08-11T00:00:00Z")
    result = dry_run_diff(manifest, output_dir, generated_at="2026-08-11T00:00:00Z")
    assert result["missing"] == []
    assert result["changed"] == []


def test_permission_matrix_is_derived_from_manifest(manifest: dict) -> None:
    matrix = render_permission_matrix(manifest)
    assert "GENERATED FROM governance/policy-manifest.yaml" in matrix
    assert "| CLINICIAN | business_table.write | DENY | - |" in matrix
    assert "| ADMIN | ops.review | ALLOW | AUDIT, OPS_INTERNAL_ONLY |" in matrix


def test_compile_then_rollback_restores_previous_version(local_tmp_dir: Path, manifest: dict) -> None:
    output_dir = local_tmp_dir / "generated"
    compile_to_directory(manifest, output_dir, generated_at="first")
    original = (output_dir / "tool-map.json").read_text(encoding="utf-8")
    changed = copy.deepcopy(manifest)
    changed["tools"][0]["domains"] = ["POLICY"]
    compile_to_directory(changed, output_dir, generated_at="second")
    assert (output_dir / "tool-map.json").read_text(encoding="utf-8") != original
    from policy_compiler import rollback_directory

    rollback_directory(output_dir)
    assert (output_dir / "tool-map.json").read_text(encoding="utf-8") == original


def test_schema_and_manifest_failures(manifest: dict) -> None:
    invalid = copy.deepcopy(manifest)
    invalid["default_policy"]["decision"] = "ALLOW"
    with pytest.raises(GovernanceError, match="default policy"):
        validate_manifest(invalid, SCHEMA)
    invalid = copy.deepcopy(manifest)
    invalid["tables"]["document"]["columns"]["id"]["classification"] = "PUBLIC"
    with pytest.raises(GovernanceError, match="classification"):
        validate_manifest(invalid, SCHEMA)
    invalid = copy.deepcopy(manifest)
    invalid["tools"][0]["roles"] = ["UNKNOWN"]
    with pytest.raises(GovernanceError, match="unknown role"):
        validate_manifest(invalid, SCHEMA)


def test_bidirectional_drift_and_undeclared_column_failure(manifest: dict) -> None:
    actual = load_schema_fixture(FIXTURE)
    assert_no_drift(manifest, actual)
    drifted = copy.deepcopy(actual)
    drifted["document"].add("not_declared")
    diff = drift_diff(manifest, drifted)
    assert diff["undeclared_column"] == [{"relation": "document", "column": "not_declared"}]
    with pytest.raises(GovernanceError, match="undeclared_column"):
        assert_no_drift(manifest, drifted)

    missing = copy.deepcopy(actual)
    missing["document"].remove("title")
    assert drift_diff(manifest, missing)["missing_column"] == [{"relation": "document", "column": "title"}]


def test_drift_exemption_requires_reason_and_can_be_used(manifest: dict) -> None:
    actual = load_schema_fixture(FIXTURE)
    actual["test_django_session"] = {"id"}
    allowed = copy.deepcopy(manifest)
    allowed["drift"]["exemptions"].append({
        "id": "test-django",
        "relation_pattern": "test_*",
        "column_pattern": "*",
        "reason": "Fixture represents Django-owned internal tables.",
        "scope": "relation-and-columns",
        "expires": "2027-01-01",
    })
    diff = drift_diff(allowed, actual)
    assert diff["undeclared_relation"] == []
    assert any(item["exemption"] == "test-django" for item in diff["exempted"])

    invalid = copy.deepcopy(manifest)
    invalid["drift"]["exemptions"][0]["reason"] = ""
    with pytest.raises(GovernanceError, match="needs a reason"):
        validate_manifest(invalid, SCHEMA)
