# Governance Metric Definitions

The BFF exposes only aggregated metric snapshots. The empty implementation is intentional until the database aggregate repository and audit event projections are configured.

| Metric family | Definition | Empty-state behavior |
|---|---|---|
| Ingestion funnel | Each stage reports input, output and drop counts; `input = output + dropped` for a closed stage | `No data`, never zero-filled as a claim |
| PHI detection | Count of detected entity types from safe detection metadata, not source text | No event rows means empty |
| Policy denials | Count of denied PDP decisions grouped by role, resource and reason code | No audit rows means empty |
| Refusal rate | Refused Agent responses divided by all completed Agent responses in the window | Requires completed and refused event totals |
| Retrieval quality | Evaluation metrics keyed by evaluation-set version and commit | No evaluation run means empty |
| De-identification quality | Precision/recall/F1 over an approved labeled evaluation set | No labeled run means empty |
| Cost | Provider token usage and cost grouped by model, role and day | No provider telemetry means empty |
| Retry cost | Retry count and additional token cost divided by completed requests | No retry telemetry means empty |

The frontend reads this BFF contract and renders an explicit empty state. It does not connect to PostgreSQL or infer a zero from a missing aggregate row.
