#!/usr/bin/env python3
"""Fail closed on public wording that overstates the software's compliance scope."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path


def forbidden_phrases() -> tuple[str, ...]:
    return (
        "HIPAA " + "compliant",
        "HIPAA-" + "compliant",
        "\u7ed7\ufe40\u608e " + "HIPAA",
        "\u6d93\u6751\u7c25\u9350\u5d07\u74e5" + "\u93c0\ue21b\u5bd4",
        "\u7487\u5a43\u67c7" + "\u5be4\u9e3f\ue185",
    )


def scan(root: Path) -> list[str]:
    findings: list[str] = []
    try:
        listing = subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=root,
            check=True,
            capture_output=True,
        ).stdout.decode("utf-8")
        paths = (root / relative for relative in listing.split("\0") if relative)
    except (OSError, subprocess.CalledProcessError):
        paths = root.glob("*.md")
    for path in paths:
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        if relative == "REQUIREMENTS-FULL.md" or relative.startswith("docs/requirements/"):
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for phrase in forbidden_phrases():
            if phrase.lower() in content.lower():
                findings.append(f"{path}: forbidden governance wording")
                break
    return findings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    findings = scan(args.root)
    if findings:
        print("\n".join(findings))
        return 1
    print("governance wording check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
