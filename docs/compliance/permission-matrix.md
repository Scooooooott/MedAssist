<!-- GENERATED FROM governance/policy-manifest.yaml -- DO NOT EDIT MANUALLY -->
# Permission Matrix

This file is generated from the governance manifest. Unknown roles and actions deny by default.

| Role | Permission | Decision | Obligations |
|---|---|---|---|
| ADMIN | answer.read | ALLOW | - |
| CLINICIAN | answer.read | ALLOW | - |
| RESEARCHER | answer.read | ALLOW | - |
| ADMIN | business_table.write | DENY | - |
| CLINICIAN | business_table.write | DENY | - |
| RESEARCHER | business_table.write | DENY | - |
| ADMIN | clinical.aggregate | DENY | AUDIT, AGGREGATE_ONLY |
| CLINICIAN | clinical.aggregate | ALLOW | AUDIT, AGGREGATE_ONLY |
| RESEARCHER | clinical.aggregate | ALLOW | AUDIT, AGGREGATE_ONLY |
| ADMIN | clinical.search | DENY | - |
| CLINICIAN | clinical.search | ALLOW | - |
| RESEARCHER | clinical.search | DENY | - |
| ADMIN | ops.review | ALLOW | AUDIT, OPS_INTERNAL_ONLY |
| CLINICIAN | ops.review | DENY | AUDIT, OPS_INTERNAL_ONLY |
| RESEARCHER | ops.review | DENY | AUDIT, OPS_INTERNAL_ONLY |
