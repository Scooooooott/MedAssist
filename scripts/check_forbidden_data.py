from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = ROOT / "data"
FORBIDDEN_PATTERNS = (
    "mimic",
    "physionet.org/files/mimic",
    "NOTEEVENTS.csv",
    "ADMISSIONS.csv",
    "PATIENTS.csv",
    "D_ICD_DIAGNOSES.csv",
)


def main() -> int:
    violations: list[Path] = []
    if DATA_DIR.exists():
        for path in DATA_DIR.rglob("*"):
            target = str(path).lower()
            if any(pattern.lower() in target for pattern in FORBIDDEN_PATTERNS):
                violations.append(path)

    if violations:
        print("Forbidden dataset indicators found:")
        for path in violations:
            print(f"- {path.relative_to(ROOT)}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
