# HIPAA-aligned Mapping

This document describes software mechanisms that are aligned with selected HIPAA safeguards. It is not a certification, legal opinion, or organizational compliance claim. Physical security, workforce procedures, contracts, and business continuity remain outside this repository.

| HIPAA area | MedAssist mechanism | Code or document location | Verification |
|---|---|---|---|
| Access control | Role and action decisions default to deny; identity claims are intended to come from the Keycloak realm | `services/identity-policy/`, `deploy/keycloak/` | PDP deny tests and Keycloak smoke test when the governance profile is available |
| Minimum necessary | Column classification and content domain are separate policy inputs; obligations can require aggregate-only output | `governance/policy-manifest.yaml`, `services/clinical-data/` | Policy compiler golden tests and clinical safety tests |
| Audit controls | Canonical audit events are chained with SHA-256 and can be verified | `services/audit-governance/` | Hash-chain concurrency and tamper-location tests |
| Integrity | Generated policy outputs are deterministic and drift-checked; audit roots can be externally anchored | `scripts/governance/`, `docs/adr/ADR-016-governance-policy-compiler.md` | Determinism, dry-run, rollback and drift tests |
| Transmission security | TLS is a deployment responsibility; service-to-service credentials are represented in the governance contract | `deploy/compose/`, `docs/architecture/` | Deployment smoke tests are environment-dependent |
| De-identification | Safe Harbor clinical projections omit direct identifiers; PHI detection results retain types/counts rather than source text | `services/clinical-data/`, `services/ingestion/`, `docs/adr/ADR-006-safe-harbor.md` | Clinical safety tests and no-PHI audit tests |
| Automatic session controls | Token expiry and session policy belong to Keycloak; the repository includes the realm contract but does not operate an identity provider here | `deploy/keycloak/` | Requires a running governance compose profile |
| Emergency access | No break-glass workflow is enabled in the demo; any future emergency role must be separately approved, time-bound, and audited | `governance/policy-manifest.yaml` | Explicitly not implemented |
| Physical and administrative safeguards | Not software-controlled by this repository | N/A | Not implemented / organizational scope |

## Safe Harbor coverage

The clinical import path is designed to remove direct identifiers and reduce quasi-identifiers: names, full dates, detailed addresses, contact values, identifiers, and free-form source text are not part of the safe projection. Birth year, truncated geography, age bands for older patients, and clinical coding values are retained only where the policy allows them. The exact upstream source license, human review, and re-identification risk assessment remain deployment responsibilities.

## Wording discipline

Public documentation must use “HIPAA-aligned” or “HIPAA alignment” and must not imply that the software alone provides organizational certification. The CI wording check is expected to scan documentation, comments and user-facing strings.
