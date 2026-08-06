# Data Flow

## Ingestion Path

1. Source documents or Synthea FHIR records are placed in local data staging.
   Failure handling: invalid source files are rejected before object storage.

2. Raw source documents are written to the versioned MinIO `raw-documents` bucket.
   Failure handling: storage failures stop the job and leave no searchable record.

3. `ingestion` starts a Spring Batch job with Postgres JobRepository state.
   Failure handling: failed jobs are restartable from the recorded step state.

4. `parser-svc` converts each document into structured IR.
   Failure handling: parse failures write a quarantine record with reason and content hash.

5. `deid-svc` detects and anonymizes PHI.
   Failure handling: timeout or exception fails closed and quarantines the document.

6. Java structure-aware chunking produces retrieval chunks with source character ranges.
   Failure handling: chunks that exceed token or span constraints are rejected before indexing.

7. `model-svc` produces embeddings for chunks.
   Failure handling: embedding failures stop the affected document version and keep it out of retrieval.

8. Postgres stores document, version, chunk, embedding, and PHI scan status records.
   Failure handling: transaction failure rolls back the document version write.

## Query Path

M1 exposes retrieval and temporary generation through `retrieval`. M3 migrates generation to `agent`.

1. User query reaches the online service.
   Failure handling: validation failures return a structured error.

2. Query embedding is requested from `model-svc`.
   Failure handling: model timeout fails the request in M1; later resilience rules distinguish safe degradation from refusal.

3. Retrieval searches Postgres with metadata filters.
   Failure handling: invalid filters fail closed.

4. M1 generation uses retrieved chunks and returns citation references.
   Failure handling: missing citation references cause abstention.

5. M3 egress checks run before external LLM calls.
   Failure handling: PHI detection errors or positive matches block the outbound call.
