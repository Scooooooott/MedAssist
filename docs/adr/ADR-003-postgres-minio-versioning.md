# ADR-003: Postgres Version Rows And MinIO Object Versioning

## Status

Accepted

## Date

2026-08-06

## Context

The system needs auditability for source documents, chunks, embeddings, and document status changes. A lake format could provide time travel, but the core online system also needs row-level security and operational simplicity.

## Decision

Use Postgres version rows for document metadata and retrieval state. Store immutable source objects in a versioned MinIO bucket. Keep Apache Iceberg as an optional M7 extension for analytical time travel.

## Rejected Alternatives

| Alternative | Rejection reason |
|---|---|
| Delta Lake as the primary storage layer | Java integration and operational complexity are high for the M0-M6 scope, while online authorization still needs Postgres. |
| Object storage only | It cannot enforce row-level access policies or support efficient metadata filtering without rebuilding database features. |
| Event sourcing for all document state | It is powerful but too much machinery before the baseline RAG path exists. |

## Consequences

The design favors simple, inspectable online state. Historical reconstruction is good enough for M0-M6 but less expressive than a lakehouse table format. Iceberg remains available as a later branch without invalidating this decision.
