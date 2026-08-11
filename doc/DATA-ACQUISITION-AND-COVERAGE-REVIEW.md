# Data Acquisition and Cross-Module Review

Date: 2026-08-10

## Scope

This record covers the implementation work completed after the M0-M3 review:

- source acquisition and normalization;
- external model asset verification;
- clinical-data test coverage and FHIR fixture compatibility;
- original-document source-range preservation after de-identification;
- cache administration, agent de-identification defaults, and reranker readiness;
- cross-module verification performed in the local Windows environment.

## Implemented

### Data acquisition

`scripts/data/fetch_data.py` now has a source-specific fetcher for every source in
`docs/DATA_SOURCES.md`:

- Synthea runs an explicitly supplied command with the reproducible default of
  1,000 patients, fixed seed, and version metadata.
- MTSamples supports an explicit file URL or the Kaggle API, with the existing
  credential and license gates plus safe ZIP extraction.
- PMC-Patients requires an explicit revision and local file or dataset URL and
  enforces a sample limit of at most 20,000 records.
- CDC, USPSTF, AHRQ, and DailyMed use explicit per-source allowlists. Whole-site
  crawling is disabled.

All network fetches retain robots.txt checks, per-host rate limiting, bounded
retries, maximum-size enforcement, atomic `.part` files, SHA-256 records, and
path traversal checks. Normalized JSONL and raw-file manifests retain source URL,
revision, retrieval time, license state, content hash, and normalization status.
For allowlisted guidance pages and overridden dataset URLs, the actual input URL
is retained per output file instead of being replaced by the source homepage.

MTSamples and DailyMed remain intentionally blocked until their conditional
license reviews are approved. PMC-Patients remains local-only. These are legal
gates, not silent implementation fallbacks.

### Model assets

`scripts/models/verify_model_assets.py` verifies externally provisioned model
bundles without network access or inference. It checks fixed model identity,
local paths, SHA-256 hashes, ONNX int8 metadata, dimensions, max length,
license approval, production restrictions, and deterministic-test restrictions.
It deliberately does not create or accept a production model manifest until a
real reviewed bundle has been provisioned.

### Clinical data and source ranges

Clinical-data tests now exercise FHIR profile validation, all supported resource
mappings, XML and JSON imports, quarantine paths, Safe Harbor fields, structured
query boundaries, JDBC result mapping, k-anonymity, timeout rounding, and audit
behavior.

The ingestion parse/de-identification boundary now writes a numeric-only source
range map into IR metadata. It maps unchanged output spans exactly and maps a
replacement span to the original PHI range. The three chunkers consume these
maps for section and table chunks. Malformed or uncovered mappings fail closed
instead of falling back to a potentially false linear offset. Tables whose
linearized text is absent are explicitly treated as generated Markdown and use
the table-level source range fallback.

The de-identification contract currently exposes original PHI spans, but not
explicit transformed-output spans. Therefore a replacement is conservatively
treated as the output between the surrounding unchanged anchors. A future
contract revision should add transformed spans if exact replacement-boundary
auditing is required.

## Verification

Passing checks:

- Python data tests: 16 passed.
- Model asset verifier tests: 7 passed.
- `parser-svc`: 18 passed, 79.03% coverage.
- `deid-svc`: 12 passed, 76.57% coverage.
- `model-svc`: 37 passed, 81.77% coverage.
- `clinical-data` reactor tests: 29 passed.
- `ingestion` reactor tests: 143 passed with Surefire temp directory redirected
  to the workspace.
- `retrieval` reactor tests: 83 passed.
- Frontend tests: 27 passed, 88.3% statement coverage.
- Frontend build, lint, and Prettier checks passed.
- Maven Spotless checks passed for ingestion and clinical-data.
- `scripts/check_forbidden_data.py` passed.

The default system temporary directory is permission-restricted in this
environment. Java tests using `@TempDir` require
`-DargLine=-Djava.io.tmpdir=L:\\MedAssist\\.maven-tmp`; pytest tests requiring
`tmp_path` require an explicit workspace `--basetemp`.

## Remaining delivery gaps

These items are recorded rather than treated as complete:

1. Real downloads were not run in this environment. They require approved
   source configuration, credentials where applicable, and explicit allowlists.
   Consequently a clean `just fetch-data` run still stops at the explicit legal
   gates for conditional sources; it is not yet a claim that every source is
   distributable.
2. No real production model bundle or license-reviewed manifest is present.
3. The full Docker/Testcontainers upload-to-retrieval E2E still requires the
   external Postgres, MinIO, and Python sidecars to be running.
4. M3 agent generation migration, durable clinical import, MCP trajectory gates,
   and the remaining production egress/injection red-team work remain separate
   delivery items documented in `doc/M3-IMPLEMENTATION-REVIEW.md`.
