# MedAssist

MedAssist is a healthcare-oriented retrieval augmented generation system for demonstrating citation-grounded answers, PHI governance, and declarative access control. It is a portfolio project and does not provide clinical decision support or medical advice.

## Current Milestone

The repository is being built from M0 upward. M0 establishes the engineering foundation; current work is completing the M1 baseline across parser, de-identification, model, ingestion, retrieval, answer generation, evaluation, and the React frontend.

## Service Ports

| Service | Port |
|---|---:|
| gateway | 8080 |
| identity-policy | 8081 |
| ingestion | 8082 |
| clinical-data | 8083 |
| retrieval | 8084 |
| agent | 8085 |
| audit-governance | 8086 |
| parser-svc | 9001 |
| deid-svc | 9002 |
| model-svc | 9003 |

## Local Commands

Use `just --list` to inspect the task entrypoints.

```bash
just bootstrap
just build
just test
just lint
just up
just down
just fetch-data-manifest
```

`just fetch-data` is intentionally fail-closed until the full reviewed download and normalization workflow is implemented. Use `just fetch-data-manifest` for the current local directory scaffold.

## Safety Boundaries

This project uses synthetic and public de-identified data only. Do not commit real PHI, MIMIC data, credentials, generated protobuf code, model weights, or downloaded source data.

The project describes itself as HIPAA-aligned. Do not use stronger organizational compliance wording in public project materials or code comments.
