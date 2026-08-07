# M1 Implementation Status

Date: 2026-08-07
Baseline: `cd4d5de Initialize M0 project foundation`

## Status

This commit records the current M1 foundation implementation. The implementation is partial: the core contracts, service scaffolding, parser/de-identification/model logic, ingestion schema and chunking, retrieval query foundation, frontend shell, and evaluation harness are present, but the full M1 acceptance workflow is not complete.

## Toolchain

The project-local toolchain is available under `.tools/` and is excluded from version control:

- Maven 3.9.11, using Java 21.
- uv 0.12.2.
- buf 1.72.0.
- just 1.58.0.

`justfile` invokes these local tools explicitly. Python cache directories are also kept under `.tools/` during local verification.

## Implemented Scope

### M1.1 Contracts and generated-code workflow

- Proto packages are organized under `contracts/proto/medassist/contracts/v1/`.
- Parser, de-identification, model, retrieval, and shared contract definitions were normalized.
- `buf lint` and `buf generate` pass.
- Python generated imports are configured through the shared proto helper and generated output remains ignored.

### M1.2 Parser service

- TXT, Markdown, and HTML parsing produce structured sections, tables, source ranges, and parser status.
- PDF parsing has an optional Docling backend and explicit unsupported/error status handling.
- The parser gRPC endpoint reads documents from object-storage URIs and does not accept document bytes in the request.
- S3/MinIO object-store access is isolated behind a small adapter.

### M1.3 De-identification service

- Production construction uses Presidio and fails closed when the production recognizer/model setup cannot be initialized.
- HMAC-based pseudonymization, date/age/ZIP safe-harbor handling, custom recognizers, and gRPC request handling are implemented.
- Regex de-identification is available only through explicit test mode or test injection.

### M1.4 Model service

- The production backend is structured around a mounted BGE-M3 ONNX int8 bundle, bounded input length, batching, warmup, and readiness state.
- Deterministic embeddings are limited to explicit test injection.
- Reranking reports `UNIMPLEMENTED` until the production reranker is supplied.
- The service README records the required external model assets.

### M1.5 Ingestion foundation

- Spring Batch, JDBC, Flyway, PostgreSQL, and H2 test wiring was added.
- The baseline schema includes documents, versions, chunks, embeddings, indexes, status fields, and pgvector configuration.
- A structure-aware chunker supports headings, sentence grouping, overlap, breadcrumbs, and table blocks.
- Ingestion application-context and chunking tests pass.

### M1.6 Retrieval foundation

- Query embeddings are requested from model-svc through the generated gRPC client with input type, model/version, and deadline checks.
- Retrieval uses parameterized JDBC SQL with pgvector cosine distance.
- Model identity, active/clean filters, document type, publisher, effective dates, and section type are pushed into the SQL query.
- A retrieval JDBC design ADR was added.
- The retrieval application context and compile checks pass.

### M1.7 Answer API and frontend shell

- A retrieval answer endpoint and browser UI expose question input, filters, answer/abstention state, timing, citations, source metadata, and quoted-span highlighting.
- The current answer service still uses a deterministic placeholder response and the current SSE route emits a single complete response rather than streaming LLM tokens.

### M1.8 Evaluation harness

- The CLI supports quick-mode confirmation, holdout metadata, input hashing, category metrics, worst-case examples, unauthorized-entity checks, and optional RAGAS status reporting.
- Harness tests pass.
- Golden/holdout datasets, persisted evaluation runs, and a live retrieval/evaluation integration are not included yet.

## Verification

The following checks passed during M1 work:

- `buf lint` and `buf generate`.
- Ingestion Maven tests, including Spring context and chunking tests.
- De-identification tests: 12 passed; coverage 75.60%.
- Model tests: 11 passed; coverage 77.31%.
- Parser functional tests: 3 passed when the coverage failure threshold was disabled for the narrow functional run. The configured threshold still exposes uncovered service/object-store/PDF/server paths.
- Retrieval Maven compile and application-context tests.
- Evaluation harness tests: 2 passed.
- `python scripts/scan_language.py`.
- `python scripts/check_forbidden_data.py`.
- `git diff --check`.

## Known Gaps and Blockers

These items are intentionally recorded rather than presented as complete M1 acceptance:

1. `IngestionBatchConfiguration` still wires the four pipeline stages to skeleton steps. Discovery, parse/de-identify orchestration, chunk/embed persistence, and index publication are not end-to-end job implementations.
2. The answer path does not yet call the configured Spring AI `ChatClient`; it returns deterministic placeholder text and does not implement structured LLM output or the full citation/abstention contract.
3. The SSE endpoint currently sends one complete answer event instead of token-level streaming with the required event lifecycle.
4. A real BGE-M3 ONNX int8 model/tokenizer bundle is not checked in or provisioned by the repository, so production model readiness cannot be verified here.
5. Presidio production models and a real production model bundle are external deployment assets; only fail-closed behavior and test-mode behavior are covered locally.
6. Retrieval has no PostgreSQL/Testcontainers integration test and no completed retrieval gRPC server implementation.
7. Parser coverage is below the configured threshold, and real PDF/object-storage/timeout integration coverage is still missing.
8. The evaluation harness has no committed golden and de-identification datasets, no database-backed run persistence, and no end-to-end RAGAS run.
9. Full M1 acceptance still requires cross-service integration tests, real dependency/model provisioning, and production-like deployment verification.

## Security and Reproducibility Notes

- Local binaries, caches, virtual environments, generated proto output, build output, and test data remain ignored.
- A staged-diff privacy/secret review is required before committing this record. Local scans found no real credentials or patient data; values such as `test-only-key` and deployment environment defaults are synthetic placeholders.
- No production API keys, model files, database dumps, or patient records are part of this commit.

## Recommended Next Work

1. Replace ingestion skeleton steps with the actual object-store, parser, de-identification, chunk, embedding, and transactional publication flow.
2. Implement the structured answer generation contract, grounded citation validation, abstention policy, and true SSE event streaming.
3. Provision test fixtures for Presidio, BGE-M3, PostgreSQL/pgvector, and object storage, then add cross-service integration tests.
4. Add committed synthetic golden/holdout datasets and persistent evaluation-run reporting.
5. Raise parser and shared-service coverage to the configured thresholds and run a production-like acceptance suite.
