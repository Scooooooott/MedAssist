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
