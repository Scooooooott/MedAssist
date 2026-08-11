# ADR-016: Governance Policy Compiler

## Status

Accepted for M4 implementation.

## Decision

`governance/policy-manifest.yaml` is the only editable policy source. A deterministic compiler produces database grants/RLS SQL, retrieval filters, Agent egress rules, tool mappings, ops-console policy and application resource/action permissions. Generated files contain a `DO NOT EDIT MANUALLY` marker; generation metadata is kept separate so timestamps do not make the actual outputs drift on every run.

The first implementation is a local, testable compiler. Database apply is an explicit operation and remains fail-closed when a database adapter is unavailable. M4 uses an in-process publisher abstraction for audit events; the transport can be replaced by Redpanda in M5 without changing service callers.

## Consequences

- Policy changes are reviewable as one manifest change plus deterministic generated diffs.
- Database permissions and application decisions can be compared during drift checks.
- A real PostgreSQL apply and Keycloak integration still require an authorized environment and are not implied by unit tests.
- The compiler must reject undeclared columns and ambiguous role/domain references rather than guessing.
