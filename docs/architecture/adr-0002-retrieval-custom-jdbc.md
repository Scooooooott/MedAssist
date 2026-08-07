# ADR-0002: Custom JDBC Repository for M1 Retrieval

## Decision

M1 uses a parameterized `NamedParameterJdbcTemplate` repository instead of Spring AI
`PgVectorStore`.

## Rationale

The M1 contract requires SQL-side filters for document type, publisher, effective-date
range, and section type, plus an exact model name/version match and PHI-clean rows. A
custom repository makes those predicates explicit and keeps cosine distance calculation
and source metadata mapping in one query. User-controlled values are bound parameters;
the SQL shape and column names are fixed in code.

## Consequences

The repository is PostgreSQL/pgvector-specific and needs a Postgres integration test
before production rollout. Hybrid retrieval, reranking, and caching remain out of scope
for M1.
