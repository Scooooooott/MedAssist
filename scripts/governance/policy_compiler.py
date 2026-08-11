"""Deterministic policy compiler and schema drift checker for the M4 governance manifest."""

from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import hashlib
import json
import shutil
import sys
from pathlib import Path
from typing import Any, Mapping

try:
    import yaml
except ImportError as exc:  # pragma: no cover - exercised only in an unprovisioned environment
    raise SystemExit("PyYAML is required; install scripts/governance/requirements.txt") from exc


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = ROOT / "governance" / "policy-manifest.yaml"
DEFAULT_SCHEMA = ROOT / "governance" / "policy-manifest.schema.json"
ARTIFACTS = (
    "sql/grants-and-rls.sql",
    "retrieval-filters.json",
    "egress-policy.json",
    "tool-map.json",
    "ops-console-policy.json",
    "application-permissions.json",
    "manifest.lock.json",
    "generation-metadata.json",
)
COLUMN_LABELS = {"PHI_DIRECT", "PHI_QUASI", "CLINICAL_FIELD", "PUBLIC_FIELD"}
DOMAIN_LABELS = {"CLINICAL", "POLICY", "DRUG_LABEL", "CASE_REPORT", "PUBLIC"}
ROLE_IDS = {"CLINICIAN", "RESEARCHER", "ADMIN"}


class GovernanceError(ValueError):
    """Raised for invalid policy input or an unsafe operation."""


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = yaml.safe_load(handle)
    if not isinstance(value, dict):
        raise GovernanceError("manifest root must be an object")
    return value


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise GovernanceError(message)


def validate_manifest(manifest: Mapping[str, Any], schema_path: Path | None = DEFAULT_SCHEMA) -> None:
    """Validate structural JSON-Schema-compatible rules plus cross-reference rules."""
    _require(isinstance(manifest, Mapping), "manifest root must be an object")
    required = {
        "version", "metadata", "default_policy", "column_classifications", "content_domains",
        "roles", "tables", "tools", "dashboards", "ops_console", "egress",
        "application_permissions", "drift",
    }
    _require(required <= set(manifest), "manifest is missing required top-level fields")
    version = manifest["version"]
    _require(isinstance(version, str) and len(version.split(".")) == 3 and all(part.isdigit() for part in version.split(".")), "version must be semantic X.Y.Z")
    _require(isinstance(manifest["metadata"], Mapping), "metadata must be an object")
    columns = manifest["column_classifications"]
    domains = manifest["content_domains"]
    _require(isinstance(columns, Mapping) and isinstance(columns.get("labels"), Mapping), "column_classifications.labels is required")
    _require(isinstance(domains, Mapping) and isinstance(domains.get("labels"), Mapping), "content_domains.labels is required")
    _require(set(columns["labels"]) == COLUMN_LABELS, "column_classifications.labels must contain exactly column labels")
    _require(set(domains["labels"]) == DOMAIN_LABELS, "content_domains.labels must contain exactly content domain labels")

    defaults = manifest["default_policy"]
    _require(isinstance(defaults, Mapping) and all(value == "DENY" for value in defaults.values()), "default policy must deny every listed fallback")

    roles = manifest["roles"]
    _require(isinstance(roles, list), "roles must be an array")
    role_ids = {role.get("id") for role in roles if isinstance(role, Mapping)}
    _require(role_ids == ROLE_IDS, "roles must define exactly CLINICIAN, RESEARCHER, and ADMIN")
    for role in roles:
        _require(set(role.get("allowed_domains", [])) <= DOMAIN_LABELS, f"unknown domain in role {role.get('id')}")
        _require(set(role.get("aggregate_only_domains", [])) <= DOMAIN_LABELS, f"unknown aggregate domain in role {role.get('id')}")

    tables = manifest["tables"]
    _require(isinstance(tables, Mapping) and tables, "tables must be a non-empty object")
    row_mappings = domains.get("row_mappings", [])
    _require(isinstance(row_mappings, list), "content_domains.row_mappings must be an array")
    mapped_relations: set[str] = set()
    for mapping in row_mappings:
        _require(isinstance(mapping, Mapping), "row mapping must be an object")
        relation = mapping.get("relation")
        _require(relation in tables, f"row mapping references unknown relation: {relation}")
        _require(relation not in mapped_relations, f"duplicate row mapping: {relation}")
        mapped_relations.add(relation)
        _require(mapping.get("default") in DOMAIN_LABELS, f"row mapping has an invalid domain: {relation}")
        if "column" in mapping:
            _require(mapping["column"] in tables[relation]["columns"], f"row mapping column is not declared: {relation}.{mapping['column']}")

    for relation, table in tables.items():
        _require(isinstance(table, Mapping), f"table must be an object: {relation}")
        table_columns = table.get("columns")
        _require(isinstance(table_columns, Mapping) and table_columns, f"table has no columns: {relation}")
        for column, definition in table_columns.items():
            _require(isinstance(definition, Mapping), f"column definition must be an object: {relation}.{column}")
            classification = definition.get("classification")
            _require(classification in columns["labels"], f"invalid column classification: {relation}.{column}")
            _require("content_domain" not in definition and "domain" not in definition, f"column and content-domain labels must remain separate: {relation}.{column}")
        access = table.get("access")
        _require(isinstance(access, Mapping), f"table access is required: {relation}")
        _require(set(access) <= ROLE_IDS, f"table access references an unknown role: {relation}")
        for role in access:
            _require(set(access[role]) <= {"SELECT"}, f"unsupported table operation in {relation}.{role}")

    for item_name in ("tools", "dashboards", "application_permissions"):
        items = manifest[item_name]
        _require(isinstance(items, list), f"{item_name} must be an array")
        for item in items:
            _require(set(item.get("roles", [])) <= ROLE_IDS, f"{item_name} has an unknown role reference")
            _require(set(item.get("domains", [])) <= DOMAIN_LABELS, f"{item_name} has an invalid content domain")
    tool_ids = [item.get("id") for item in manifest["tools"]]
    _require(len(tool_ids) == len(set(tool_ids)), "tool ids must be unique")

    ops = manifest["ops_console"]
    _require(set(ops.get("roles", [])) <= ROLE_IDS, "ops console has an unknown role")
    _require(set(ops.get("read_relations", [])) <= set(tables), "ops console references an unknown relation")
    _require(ops.get("direct_writes") is False and ops.get("public_exposure") is False, "ops console must deny direct writes and public exposure")

    egress = manifest["egress"]
    _require(egress.get("default") == "DENY", "egress default must deny")
    destinations = egress.get("destinations", [])
    _require(isinstance(destinations, list), "egress destinations must be an array")
    _require(all(set(item.get("content_classes", [])) <= set(egress.get("content_classes", [])) for item in destinations), "egress destination classes must be globally declared")

    drift = manifest["drift"]
    _require(drift.get("schema_format") == "relation-columns-v1", "unsupported drift schema format")
    for exemption in drift.get("exemptions", []):
        _require(bool(str(exemption.get("reason", "")).strip()) and len(exemption["reason"]) >= 10, f"drift exemption needs a reason: {exemption.get('id')}")
        try:
            dt.date.fromisoformat(exemption["expires"])
        except (TypeError, ValueError) as exc:
            raise GovernanceError(f"drift exemption has an invalid expiry: {exemption.get('id')}") from exc

    if schema_path and schema_path.exists():
        try:
            import jsonschema  # type: ignore
        except ImportError:
            return
        with schema_path.open("r", encoding="utf-8") as handle:
            schema = json.load(handle)
        errors = sorted(jsonschema.Draft202012Validator(schema).iter_errors(manifest), key=lambda error: list(error.path))
        if errors:
            raise GovernanceError("schema validation failed: " + "; ".join(error.message for error in errors[:5]))


def validate_manifest_file(path: Path = DEFAULT_MANIFEST, schema_path: Path | None = DEFAULT_SCHEMA) -> dict[str, Any]:
    manifest = load_yaml(path)
    validate_manifest(manifest, schema_path)
    return manifest


def _digest(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _json_artifact(payload: Any, metadata: Mapping[str, Any]) -> str:
    body = {"_generated_header": "DO NOT EDIT: generated by scripts/governance/policy_compiler.py", "metadata": dict(metadata), "policy": payload}
    return json.dumps(body, ensure_ascii=True, indent=2) + "\n"


def _sql_identifier(value: str) -> str:
    _require(value.replace("_", "").isalnum() and value[0].islower(), f"unsafe SQL identifier: {value}")
    return '"' + value + '"'


def _role_name(role: str) -> str:
    return "medassist_" + role.lower()


def compile_outputs(manifest: Mapping[str, Any], generated_at: str | None = None) -> dict[str, str]:
    validate_manifest(manifest)
    metadata: dict[str, Any] = {"manifest_version": manifest["version"], "manifest_sha256": _digest(manifest)}

    sql: list[str] = ["-- DO NOT EDIT: generated by scripts/governance/policy_compiler.py", "-- Policy content is deterministic; metadata is stored in manifest.lock.json.", "BEGIN;", ""]
    sql.append("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;")
    sql.append("REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;")
    tables = manifest["tables"]
    roles = {role["id"]: role for role in manifest["roles"]}
    row_mapping = {item["relation"]: item for item in manifest["content_domains"]["row_mappings"]}
    for relation in sorted(tables):
        quoted_relation = _sql_identifier(relation)
        is_view = tables[relation].get("kind") == "view"
        if not is_view:
            sql.append(f"ALTER TABLE public.{quoted_relation} ENABLE ROW LEVEL SECURITY;")
            for role in sorted(roles):
                sql.append(f"DROP POLICY IF EXISTS {_sql_identifier('policy_' + relation + '_' + role.lower())} ON public.{quoted_relation};")
        for role in sorted(tables[relation].get("access", {})):
            columns = tables[relation]["columns"]
            aggregate = role == "RESEARCHER" and row_mapping.get(relation, {}).get("default") == "CLINICAL"
            allowed_classes = {"PUBLIC_FIELD", "CLINICAL_FIELD"} if role != "CLINICIAN" else {"PHI_QUASI", "CLINICAL_FIELD", "PUBLIC_FIELD"}
            selected = [name for name in sorted(columns) if columns[name]["classification"] in allowed_classes]
            if not selected:
                continue
            role_sql = _sql_identifier(_role_name(role))
            column_sql = ", ".join(_sql_identifier(name) for name in selected)
            sql.append(f"GRANT SELECT ({column_sql}) ON TABLE public.{quoted_relation} TO {role_sql};")
            if not is_view:
                policy_name = _sql_identifier("policy_" + relation + "_" + role.lower())
                condition = f"current_setting('app.role', true) = '{role}'"
                mapping = row_mapping.get(relation, {})
                if mapping.get("column"):
                    condition += f" AND {_sql_identifier(mapping['column'])} = ANY (string_to_array(current_setting('app.allowed_domains', true), ','))"
                elif aggregate:
                    condition += " AND current_setting('app.aggregate_only', true) = 'true'"
                sql.append(f"CREATE POLICY {policy_name} ON public.{quoted_relation} FOR SELECT TO {role_sql} USING ({condition});")
    sql.extend(["", "COMMIT;", ""])

    role_domains = {role["id"]: sorted(role.get("allowed_domains", [])) for role in manifest["roles"]}
    filters = {
        role: {
            "default_decision": "DENY",
            "domains": domains,
            "tools": sorted(tool["id"] for tool in manifest["tools"] if role in tool["roles"]),
            "relations": sorted(relation for relation, table in tables.items() if role in table.get("access", {})),
            "aggregate_only_domains": sorted(next(item for item in manifest["roles"] if item["id"] == role).get("aggregate_only_domains", [])),
        }
        for role, domains in sorted(role_domains.items())
    }
    tool_map = {tool["id"]: {key: tool[key] for key in ("roles", "domains", "query_classifications", "aggregate_only")} for tool in sorted(manifest["tools"], key=lambda item: item["id"])}
    ops_policy = {key: manifest["ops_console"][key] for key in ("enabled", "roles", "read_relations", "direct_writes", "state_changes_via", "public_exposure")}
    permissions = {item["id"]: {key: item[key] for key in ("roles", "domains", "decision") if key in item} | ({"obligations": item["obligations"]} if "obligations" in item else {}) for item in sorted(manifest["application_permissions"], key=lambda item: item["id"])}
    outputs = {
        "sql/grants-and-rls.sql": "\n".join(sql),
        "retrieval-filters.json": _json_artifact(filters, metadata),
        "egress-policy.json": _json_artifact(manifest["egress"], metadata),
        "tool-map.json": _json_artifact(tool_map, metadata),
        "ops-console-policy.json": _json_artifact(ops_policy, metadata),
        "application-permissions.json": _json_artifact(permissions, metadata),
        "manifest.lock.json": _json_artifact({"artifacts": list(ARTIFACTS), "manifest_sha256": metadata["manifest_sha256"]}, metadata),
        "generation-metadata.json": _json_artifact(
            {
                "generated_at": generated_at or "UNSPECIFIED",
                "generator": "scripts/governance/policy_compiler.py",
            },
            metadata,
        ),
    }
    return outputs


def dry_run_diff(
    manifest: Mapping[str, Any], output_dir: Path, generated_at: str | None = None
) -> dict[str, list[str]]:
    """Compare deterministic generated content without creating or changing files."""
    outputs = compile_outputs(manifest, generated_at)
    result = {"missing": [], "changed": [], "unchanged": []}
    for relative, content in sorted(outputs.items()):
        path = output_dir / relative
        if not path.exists():
            result["missing"].append(relative)
        elif path.read_text(encoding="utf-8") == content:
            result["unchanged"].append(relative)
        else:
            result["changed"].append(relative)
    return result


def render_permission_matrix(manifest: Mapping[str, Any]) -> str:
    """Render the review matrix from the manifest instead of hand-maintained expectations."""
    validate_manifest(manifest)
    roles = sorted(role["id"] for role in manifest["roles"])
    lines = [
        "<!-- GENERATED FROM governance/policy-manifest.yaml -- DO NOT EDIT MANUALLY -->",
        "# Permission Matrix",
        "",
        "This file is generated from the governance manifest. Unknown roles and actions deny by default.",
        "",
        "| Role | Permission | Decision | Obligations |",
        "|---|---|---|---|",
    ]
    for permission in sorted(manifest["application_permissions"], key=lambda item: item["id"]):
        allowed_roles = set(permission.get("roles", []))
        obligations = ", ".join(permission.get("obligations", [])) or "-"
        for role in roles:
            decision = "ALLOW" if permission.get("decision") == "ALLOW" and role in allowed_roles else "DENY"
            lines.append(f"| {role} | {permission['id']} | {decision} | {obligations} |")
    return "\n".join(lines) + "\n"


def write_outputs(output_dir: Path, outputs: Mapping[str, str]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    for relative, content in outputs.items():
        path = output_dir / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")


def compile_to_directory(manifest: Mapping[str, Any], output_dir: Path, generated_at: str | None = None) -> dict[str, str]:
    outputs = compile_outputs(manifest, generated_at)
    existing = [output_dir / relative for relative in ARTIFACTS if (output_dir / relative).exists()]
    if existing:
        lock = output_dir / "manifest.lock.json"
        digest = "previous"
        if lock.exists():
            try:
                digest = json.loads(lock.read_text(encoding="utf-8"))["policy"]["manifest_sha256"]
            except (KeyError, json.JSONDecodeError):
                pass
        snapshot = output_dir / ".rollback" / digest
        snapshot.mkdir(parents=True, exist_ok=True)
        for path in existing:
            target = snapshot / path.relative_to(output_dir)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    write_outputs(output_dir, outputs)
    return outputs


def rollback_directory(output_dir: Path) -> None:
    rollback_root = output_dir / ".rollback"
    candidates = sorted((path for path in rollback_root.iterdir() if path.is_dir()), key=lambda path: path.stat().st_mtime_ns, reverse=True) if rollback_root.exists() else []
    _require(bool(candidates), "no rollback snapshot is available")
    snapshot = candidates[0]
    for relative in ARTIFACTS:
        source = snapshot / relative
        _require(source.exists(), f"rollback snapshot is incomplete: {relative}")
    for relative in ARTIFACTS:
        target = output_dir / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(snapshot / relative, target)


def load_schema_fixture(path: Path) -> dict[str, set[str]]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    relations = value.get("relations") if isinstance(value, Mapping) else None
    _require(isinstance(relations, Mapping), "schema fixture must contain relations")
    result: dict[str, set[str]] = {}
    for relation, definition in relations.items():
        columns = definition.get("columns") if isinstance(definition, Mapping) else definition
        _require(isinstance(columns, list) and all(isinstance(column, str) for column in columns), f"invalid fixture columns: {relation}")
        result[str(relation)] = set(columns)
    return result


def drift_diff(manifest: Mapping[str, Any], actual: Mapping[str, set[str]]) -> dict[str, list[dict[str, str]]]:
    validate_manifest(manifest)
    declared = {relation: set(table["columns"]) for relation, table in manifest["tables"].items()}
    exemptions = manifest["drift"].get("exemptions", [])

    def exempt(relation: str, column: str | None, scope: str) -> Mapping[str, Any] | None:
        for item in exemptions:
            if fnmatch.fnmatchcase(relation, item["relation_pattern"]) and fnmatch.fnmatchcase(column or "", item["column_pattern"]):
                if scope == "relation" and item["scope"] == "columns-only":
                    continue
                return item
        return None

    diffs: dict[str, list[dict[str, str]]] = {"missing_relation": [], "undeclared_relation": [], "missing_column": [], "undeclared_column": [], "exempted": []}
    for relation in sorted(set(declared) | set(actual)):
        if relation not in declared:
            item = exempt(relation, None, "relation")
            if item:
                diffs["exempted"].append({"kind": "undeclared_relation", "relation": relation, "exemption": item["id"]})
            else:
                diffs["undeclared_relation"].append({"relation": relation})
            continue
        if relation not in actual:
            item = exempt(relation, None, "relation")
            if item:
                diffs["exempted"].append({"kind": "missing_relation", "relation": relation, "exemption": item["id"]})
            else:
                diffs["missing_relation"].append({"relation": relation})
            continue
        for column in sorted(declared[relation] - actual[relation]):
            item = exempt(relation, column, "column")
            (diffs["exempted"] if item else diffs["missing_column"]).append({"relation": relation, "column": column, **({"exemption": item["id"]} if item else {})})
        for column in sorted(actual[relation] - declared[relation]):
            item = exempt(relation, column, "column")
            (diffs["exempted"] if item else diffs["undeclared_column"]).append({"relation": relation, "column": column, **({"exemption": item["id"]} if item else {})})
    return diffs


def assert_no_drift(manifest: Mapping[str, Any], actual: Mapping[str, set[str]]) -> None:
    diff = drift_diff(manifest, actual)
    failures = [f"{kind}: {item}" for kind, items in diff.items() if kind != "exempted" for item in items]
    if failures:
        raise GovernanceError("schema drift detected: " + "; ".join(failures))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("validate")
    compile_parser = sub.add_parser("compile")
    compile_parser.add_argument("--output-dir", type=Path, required=True)
    compile_parser.add_argument("--generated-at")
    dry_parser = sub.add_parser("dry-run")
    dry_parser.add_argument("--output-dir", type=Path)
    dry_parser.add_argument("--generated-at")
    rollback_parser = sub.add_parser("rollback")
    rollback_parser.add_argument("--output-dir", type=Path, required=True)
    drift_parser = sub.add_parser("drift")
    drift_parser.add_argument("--fixture", type=Path, required=True)
    matrix_parser = sub.add_parser("matrix")
    matrix_parser.add_argument("--output", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        manifest = validate_manifest_file(args.manifest, args.schema)
        if args.command == "validate":
            print(f"valid: {args.manifest}")
        elif args.command == "compile":
            compile_to_directory(manifest, args.output_dir, args.generated_at)
            print(f"compiled: {args.output_dir}")
        elif args.command == "dry-run":
            output_dir = args.output_dir or Path(".")
            print(
                json.dumps(
                    {"output_dir": str(output_dir), **dry_run_diff(manifest, output_dir, args.generated_at)},
                    ensure_ascii=True,
                    indent=2,
                )
            )
        elif args.command == "rollback":
            rollback_directory(args.output_dir)
            print(f"rolled back: {args.output_dir}")
        elif args.command == "drift":
            diff = drift_diff(manifest, load_schema_fixture(args.fixture))
            failures = {key: value for key, value in diff.items() if value and key != "exempted"}
            print(json.dumps(diff, ensure_ascii=True, indent=2, sort_keys=True))
            if failures:
                return 1
        elif args.command == "matrix":
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(render_permission_matrix(manifest), encoding="utf-8", newline="\n")
            print(f"matrix: {args.output}")
        return 0
    except (GovernanceError, OSError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
