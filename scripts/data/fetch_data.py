from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = ROOT / "data"
SOURCES = {
    "synthea": "https://github.com/synthetichealth/synthea",
    "mtsamples": "https://www.kaggle.com/datasets/tboyle10/medicaltranscriptions",
    "pmc-patients": "https://huggingface.co/datasets/THUMedInfo/PMC-Patients",
    "cdc": "https://www.cdc.gov/",
    "uspstf": "https://www.uspreventiveservicestaskforce.org/",
    "ahrq": "https://www.ahrq.gov/",
    "dailymed": "https://dailymed.nlm.nih.gov/",
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare local dataset work directories.")
    parser.add_argument("--manifest-only", action="store_true", help="Write source manifest only.")
    args = parser.parse_args()

    DATA_DIR.mkdir(exist_ok=True)
    for name in SOURCES:
        (DATA_DIR / name).mkdir(exist_ok=True)

    manifest = {
        "created_at": datetime.now(tz=UTC).isoformat(),
        "manifest_only": args.manifest_only,
        "sources": SOURCES,
        "notes": [
            "This M0 script prepares reproducible local data directories.",
            "Network download and normalization steps are implemented in M1 ingestion tasks.",
            "NICE source text is intentionally not downloaded or redistributed.",
        ],
    }
    (DATA_DIR / "source-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
