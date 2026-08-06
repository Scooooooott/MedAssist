# ADR-006: Safe Harbor De-Identification Path

## Status

Accepted

## Date

2026-08-06

## Context

The system demonstrates PHI governance without processing real PHI. It still needs a concrete, testable de-identification policy for synthetic and public de-identified examples.

## Decision

Use the HIPAA Safe Harbor de-identification path as the implementation target. The project is HIPAA-aligned and does not claim organizational compliance.

## Rejected Alternatives

| Alternative | Rejection reason |
|---|---|
| Expert Determination | It requires qualified statistical expert review that is outside the scope of this portfolio project. |
| Best-effort NER only | It would not give a complete checklist for implementation or testing. |
| Persist reversible re-identification vault by default | It expands the trust boundary and creates avoidable storage risk. |

## Identifier Handling Table

| Safe Harbor identifier | M1/M3 handling rule |
|---|---|
| Names | Replace with deterministic surrogate or redact. |
| Geographic subdivisions smaller than state | Keep state only; ZIP keeps first 3 digits only when population threshold is met, otherwise `000`. |
| All date elements except year | Keep year only or apply patient-constant date shift; never store exact day/month. |
| Telephone numbers | Replace or redact. |
| Fax numbers | Replace or redact. |
| Email addresses | Replace or redact. |
| Social Security numbers | Replace or redact. |
| Medical record numbers | Replace or redact. |
| Health plan beneficiary numbers | Replace or redact. |
| Account numbers | Replace or redact. |
| Certificate or license numbers | Replace or redact. |
| Vehicle identifiers and serial numbers | Replace or redact. |
| Device identifiers and serial numbers | Replace or redact. |
| URLs | Replace or redact unless explicitly public source metadata. |
| IP addresses | Replace or redact. |
| Biometric identifiers | Reject document for quarantine in M1 scope. |
| Full-face photos and comparable images | Reject document for quarantine; image processing is out of scope. |
| Other unique identifying numbers, characteristics, or codes | Replace or redact; unknown unique identifiers fail closed. |

Age greater than 89 is grouped as `90+`.

## Consequences

Safe Harbor gives implementable rules and testable coverage. It can be stricter than necessary for some public de-identified sources, but the consistency is useful for a demo focused on governance.
