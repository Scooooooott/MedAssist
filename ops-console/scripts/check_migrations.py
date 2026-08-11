#!/usr/bin/env python3
"""Fail closed if Django starts owning business schema or tables."""

from __future__ import annotations

import ast
import sys
from pathlib import Path


BUSINESS_TABLES = {"feedback_review_queue", "quarantine", "document_metadata_review", "evaluation_candidate"}


def _is_model_class(node: ast.ClassDef) -> bool:
    return any(isinstance(base, ast.Attribute) and base.attr == "Model" for base in node.bases)


def check_models(models_path: Path) -> list[str]:
    errors: list[str] = []
    tree = ast.parse(models_path.read_text(encoding="utf-8"), filename=str(models_path))
    for node in tree.body:
        if not isinstance(node, ast.ClassDef) or not _is_model_class(node):
            continue
        meta = next((item for item in node.body if isinstance(item, ast.ClassDef) and item.name == "Meta"), None)
        managed = next(
            (
                assignment.value.value
                for assignment in (meta.body if meta else [])
                if isinstance(assignment, ast.Assign)
                and any(isinstance(target, ast.Name) and target.id == "managed" for target in assignment.targets)
                and isinstance(assignment.value, ast.Constant)
            ),
            None,
        )
        if managed is not False:
            errors.append(f"{models_path}: {node.name} must declare Meta.managed = False")
    return errors


def check_migrations(root: Path) -> list[str]:
    errors: list[str] = []
    for migration in root.glob("**/migrations/*.py"):
        if migration.name == "__init__.py":
            continue
        content = migration.read_text(encoding="utf-8").lower()
        if "createmodel" in content or any(table in content for table in BUSINESS_TABLES):
            errors.append(f"{migration}: business-table Django migrations are forbidden")
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    errors = check_models(root / "console" / "models.py") + check_migrations(root)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("ops-console schema boundary check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
