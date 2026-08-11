# Retrieval Service

The retrieval service owns vector retrieval, metadata filtering, and retrieval orchestration.

// TEMPORARY: moves to agent-service in M3

During M1, minimal answer generation may live here only to establish the baseline RAG path. It must be migrated to the agent service in M3.

## M1 Limitation

M1 does not include the egress PHI guard. Public deployment is blocked until M3.9. Use only de-identified corpus content and avoid sending user-entered PHI to any external LLM provider.

## Endpoints

- `POST /api/search`
- `POST /api/answer`
- `POST /api/answer/stream`

The current M1 implementation keeps provider configuration externalized and returns abstention when no valid retrieved citation exists.

## M3.7 Citation Validation

`CitationValidator.validate` remains the compatibility entry point. It matches only
against `RetrievedChunk.text`, using exact substring matching after a bounded
normalization of Unicode compatibility forms, case, whitespace, and common quote,
dash, and ellipsis punctuation. Metadata and contextual prefixes are never evidence.

For sentence-level coverage, callers bind citations explicitly to substantive
`CitationAssertion` values and call `validateWithCoverage`. Blank assertions are
excluded; an assertion is covered when at least one bound citation is valid. The
coverage is `covered assertions / substantive assertions`, with an empty assertion
set defined as 100% coverage. The default threshold is 0.8 and can be overridden per
call. `INVALID_CITATION` and `INSUFFICIENT_COVERAGE` are separate report statuses.
Superseded or stale evidence remains valid but includes a freshness warning.
