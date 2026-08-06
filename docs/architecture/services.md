# Service Catalog

## gateway

Responsible for the public REST entrypoint, routing, rate limits, JWT validation, and edge error contracts. It does not own business workflows, model calls, or audit storage.

Upstream: users. Downstream: Java services. Data ownership: none.

## identity-policy

Responsible for identity integration, policy decisions, and future policy compilation support. It does not store clinical content or perform retrieval.

Upstream: gateway and service PEPs. Downstream: Keycloak and policy storage. Data ownership: policy decisions and identity metadata.

## ingestion

Responsible for batch document ingestion, parser/de-identification/model sidecar orchestration, chunk persistence, and quarantine on failure. It does not serve online user queries.

Upstream: local jobs and source data. Downstream: parser-svc, deid-svc, model-svc, MinIO, Postgres. Data ownership: document versions, chunks, ingestion status.

## clinical-data

Responsible for Synthea FHIR import and structured clinical query surfaces beginning in M3. It does not perform free-form RAG generation.

Upstream: ingestion or import jobs. Downstream: Postgres. Data ownership: structured clinical tables and views.

## retrieval

Responsible for vector retrieval, metadata filtering, and M1 temporary generation. It does not own identity policy, FHIR ingestion, or long-term agent orchestration.

Upstream: gateway or agent. Downstream: Postgres, model-svc, Redis. Data ownership: retrieval indexes and query-time retrieval results.

## agent

Responsible for orchestration graphs, tools, advisors, citation gates, and egress checks beginning in M3. It does not parse documents or store raw PHI.

Upstream: gateway. Downstream: retrieval, clinical-data, deid-svc, external LLM API. Data ownership: checkpoint metadata after entrance de-identification.

## audit-governance

Responsible for audit events, governance metrics, reports, dashboards, and feedback workflows. It does not directly change retrieval indexes from user feedback.

Upstream: all services. Downstream: Postgres and Redpanda. Data ownership: audit records, governance aggregates, feedback review state.

## parser-svc

Responsible for converting PDFs, HTML, and text into structured IR. It does not detect PHI or write persistent storage.

Upstream: ingestion. Downstream: none. Data ownership: none; stateless sidecar.

## deid-svc

Responsible for PHI detection and anonymization metadata without returning raw PHI values. It does not store reversible mappings by default.

Upstream: ingestion and agent egress checks. Downstream: none. Data ownership: none; stateless sidecar.

## model-svc

Responsible for embeddings and future reranking inference. It does not perform retrieval filtering or answer generation.

Upstream: ingestion and retrieval. Downstream: local model runtime. Data ownership: none; stateless sidecar.
