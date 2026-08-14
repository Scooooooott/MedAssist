from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CJK_RE = re.compile(r"[\u3400-\u9fff]")
INCLUDED_SUFFIXES = {
    ".java",
    ".py",
    ".md",
    ".yml",
    ".yaml",
    ".toml",
    ".ts",
    ".tsx",
    ".css",
    ".html",
    ".json",
    ".cjs",
    ".mjs",
}
EXCLUDED_PARTS = {
    ".git",
    ".tools",
    ".venv",
    ".pytest_cache",
    ".mypy_cache",
    "node_modules",
    "coverage",
    "target",
    "__pycache__",
    "data",
    "doc",
}
EXCLUDED_PREFIXES = {
    ("frontend", "dist"),
    ("docs", "internal"),
}
EXCLUDED_FILES = {"REQUIREMENTS-FULL.md"}


def is_excluded(path: Path) -> bool:
    relative_parts = path.relative_to(ROOT).parts
    if path.name in EXCLUDED_FILES:
        return True
    if any(part in EXCLUDED_PARTS for part in relative_parts):
        return True
    return any(relative_parts[: len(prefix)] == prefix for prefix in EXCLUDED_PREFIXES)


def scan_files() -> list[tuple[str, int, str]]:
    violations: list[tuple[str, int, str]] = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix not in INCLUDED_SUFFIXES or is_excluded(path):
            continue
        for line_number, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
            if CJK_RE.search(line):
                violations.append((str(path.relative_to(ROOT)), line_number, line.strip()))
    return violations


def scan_commits(range_spec: str) -> list[tuple[str, int, str]]:
    result = subprocess.run(
        ["git", "log", "--format=%h%x00%s%x00%b%x00END", range_spec],
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        result = subprocess.run(
            ["git", "log", "-n", "50", "--format=%h%x00%s%x00%b%x00END"],
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    violations: list[tuple[str, int, str]] = []
    for entry in result.stdout.split("\x00END\n"):
        if not entry.strip():
            continue
        parts = entry.split("\x00", 2)
        if len(parts) < 3:
            continue
        commit, subject, body = parts
        message = "\n".join([subject, body])
        for line_number, line in enumerate(message.splitlines(), 1):
            if CJK_RE.search(line):
                violations.append((f"commit {commit}", line_number, line.strip()))
    return violations


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Scan public project files and commit messages for CJK text.")
    parser.add_argument("--commits", action="store_true", help="Also scan recent commit messages.")
    parser.add_argument("--commit-range", default="origin/main..HEAD", help="Git commit range to scan.")
    args = parser.parse_args()

    violations = scan_files()
    if args.commits:
        violations.extend(scan_commits(args.commit_range))

    if violations:
        print("CJK text found in public project files:")
        for path, line_number, line in violations[:50]:
            print(f"- {path}:{line_number}: {line}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
