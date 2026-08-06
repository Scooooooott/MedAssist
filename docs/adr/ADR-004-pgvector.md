# ADR-004: pgvector As The Vector Store

## Status

Accepted

## Date

2026-08-06

## Context

The retrieval layer must combine embeddings, metadata filters, document status, sensitivity-derived access rules, and future row-level security. Keeping those concerns in one transactional database reduces the chance of policy drift.

## Decision

Use PostgreSQL 17 with pgvector for M1-M6 vector storage and search.

## Rejected Alternatives

| Alternative | Rejection reason |
|---|---|
| Qdrant | Strong vector features, but access policy would live outside the primary database and require duplicate enforcement. |
| Milvus | Operationally heavier than needed for a portfolio-scale single-node deployment. |
| Elasticsearch/OpenSearch vectors | Useful for lexical search, but row-level security and relational governance would be less direct than Postgres. |

## Consequences

Postgres may not match specialized vector databases at large scale. The tradeoff is acceptable because this project values governance correctness, RLS, metadata joins, and deployment simplicity over massive vector throughput.
