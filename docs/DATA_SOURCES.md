# Data Sources

Retrieval corpora are stored locally under `data/` and are not committed. This file records licensing decisions for M0.

| Source | URL | License type | Redistribute raw data | Retrieval use | Third-party LLM allowed | Retrieved on | Planned scale |
|---|---|---|---|---|---|---|---|
| Synthea | https://github.com/synthetichealth/synthea | Apache-2.0 synthetic generator and outputs | Yes, but commit scripts only | FHIR R4 and CSV synthetic EHR | Yes, synthetic only | 2026-08-06 | 1,000 patients |
| MTSamples | https://www.kaggle.com/datasets/tboyle10/medicaltranscriptions | CC0 public-domain dataset mirror; verify source page before release | Yes if source review remains CC0 | De-identified clinical note examples | Yes after local PHI scan | 2026-08-06 | Up to 4,999 notes |
| PMC-Patients | https://huggingface.co/datasets/THUMedInfo/PMC-Patients | CC-BY-NC-SA-4.0 | No for commercial demo; store local sample only | Case-report summaries | No for commercial-provider evaluation unless separately approved | 2026-08-06 | Up to 20,000 rows |
| CDC | https://www.cdc.gov/ | U.S. federal public-domain content unless marked otherwise | Yes for public-domain pages | Public health guidance | Yes after attribution and source review | 2026-08-06 | Selected guidance pages |
| USPSTF | https://www.uspreventiveservicestaskforce.org/ | U.S. federal public-domain content unless marked otherwise | Yes for public-domain pages | Preventive care recommendations | Yes after attribution and source review | 2026-08-06 | Selected recommendations |
| AHRQ | https://www.ahrq.gov/ | U.S. federal public-domain content unless marked otherwise | Yes for public-domain pages | Evidence and policy guidance | Yes after attribution and source review | 2026-08-06 | Selected guidance pages |
| FDA DailyMed | https://dailymed.nlm.nih.gov/ | U.S. National Library of Medicine drug-label resource; label content may include submitter material | Scripts only until label-specific review | Drug label corpus | Yes only for reviewed labels | 2026-08-06 | Selected labels |
| NICE | https://www.nice.org.uk/ | NICE reuse terms with international and AI restrictions | No | Optional local-only guideline comparison | No without explicit license | 2026-08-06 | Scripts only |

## Prohibited Data

MIMIC datasets and real PHI are prohibited in the repository, test fixtures, and deployment environments. `scripts/check_forbidden_data.py` scans local data paths for common forbidden indicators.
