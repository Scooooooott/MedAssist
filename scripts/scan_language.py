from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CJK_RE = re.compile(r"[\u3400-\u9fff]")
INCLUDED_SUFFIXES = {".java", ".py", ".md", ".yml", ".yaml", ".toml"}
EXCLUDED_PARTS = {
    ".git",
    ".tools",
    ".venv",
    ".pytest_cache",
    ".mypy_cache",
    "target",
    "__pycache__",
    "data",
    "docs/internal",
}
EXCLUDED_FILES = {"REQUIREMENTS-FULL.md"}


def is_excluded(path: Path) -> bool:
    relative = path.relative_to(ROOT).as_posix()
    if path.name in EXCLUDED_FILES:
        return True
    return any(part in relative for part in EXCLUDED_PARTS)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    violations: list[tuple[Path, int, str]] = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix not in INCLUDED_SUFFIXES or is_excluded(path):
            continue
        for line_number, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            if CJK_RE.search(line):
                violations.append((path, line_number, line.strip()))

    if violations:
        print("CJK text found in public project files:")
        for path, line_number, line in violations[:50]:
            print(f"- {path.relative_to(ROOT)}:{line_number}: {line}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
