# ADR-009: Flyway Owns Business Schema, Django Models Are Unmanaged

## Status

Proposed

## Date

2026-08-07

## Context

The project uses Flyway as the single owner of database schema changes. The internal ops console needs to inspect and display business tables, but allowing Django migrations to manage those tables would create a second schema owner.

A second owner would weaken reproducibility, break drift checks, and make it unclear whether Java migrations or Django migrations define the source of truth.

## Decision

Flyway remains the only owner of business schema. Django uses `inspectdb` to generate models for business tables, and those models must have `managed = False`. Django must never create migrations for business tables.

Django's own framework tables, such as `django_*`, are allowed only for the console's internal operation. They must be listed in the governance drift-check exemption list with a documented reason.

## Rejected Alternatives

| Alternative | Rejection reason |
|---|---|
| Let Django migrations manage business tables | This creates two schema owners and bypasses the Flyway-based migration discipline. |
| Manually maintain Django models independent of the database | Manual model drift would be likely and hard to detect. `inspectdb` makes the relationship explicit. |
| Disable Django framework tables entirely | The console still needs framework metadata such as sessions or admin bookkeeping. These tables are acceptable if they are scoped and exempted explicitly. |

## Consequences

Business schema remains reproducible and auditable through Flyway. Django can still render internal admin views over existing tables. CI must enforce that business tables do not have Django migration files and that generated unmanaged models are not treated as schema authority.
