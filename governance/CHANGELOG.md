# Governance Changelog

## 1.0.0 - 2026-08-11

- Added the M4 governance manifest with separate column classifications and content domains.
- Added JSON Schema and cross-reference validation for deny-by-default roles, tools, dashboards, ops access, egress, and application permissions.
- Added deterministic policy compilation for SQL grants/RLS, retrieval filters, egress, tool maps, ops-console policy, and application permissions.
- Added `validate`, `compile`, `dry-run`, `rollback`, and `drift` CLI commands.
- Added bidirectional relation/column drift checking with expiring, reasoned exemptions and M1/M3 schema fixtures.
- Scope is limited to repository migration evidence; real PostgreSQL execution, RLS enforcement, Keycloak, production dashboards, Django inspection, and external provider E2E remain external capabilities.
