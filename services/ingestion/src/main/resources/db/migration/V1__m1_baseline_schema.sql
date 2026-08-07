CREATE TABLE IF NOT EXISTS document (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  source_system TEXT NOT NULL,
  source_uri TEXT NOT NULL,
  doc_type TEXT NOT NULL,
  publisher TEXT NOT NULL,
  title TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (source_system, source_uri)
);

COMMENT ON TABLE document IS 'Logical document identity shared by all versions.';

CREATE TABLE IF NOT EXISTS document_version (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id UUID NOT NULL REFERENCES document(id),
  version TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  effective_date DATE,
  retrieved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'WITHDRAWN')),
  superseded_by UUID REFERENCES document_version(id),
  storage_uri TEXT NOT NULL,
  content_domain TEXT NOT NULL DEFAULT 'PUBLIC',
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE (document_id, content_hash)
);

COMMENT ON COLUMN document_version.content_domain IS 'Row-level content domain used by retrieval filters and future M4 RLS. ColumnClassification stays in the policy manifest, not this table.';

CREATE INDEX IF NOT EXISTS ix_document_version_status_effective
  ON document_version(status, effective_date);

CREATE TABLE IF NOT EXISTS chunk (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  section_path TEXT NOT NULL,
  text TEXT NOT NULL,
  token_count INTEGER NOT NULL CHECK (token_count >= 0),
  content_domain TEXT NOT NULL DEFAULT 'PUBLIC',
  source_char_start BIGINT NOT NULL CHECK (source_char_start >= 0),
  source_char_end BIGINT NOT NULL CHECK (source_char_end >= source_char_start),
  phi_scan_status TEXT NOT NULL CHECK (phi_scan_status IN ('CLEAN', 'SUSPECT', 'FAILED')),
  phi_entity_types TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_version_id, ordinal)
);

COMMENT ON TABLE chunk IS 'Searchable de-identified text chunks. source_char_start/end anchor evaluation truth to source document character ranges.';
COMMENT ON COLUMN chunk.content_domain IS 'Row-level domain tag for retrieval filtering and future RLS. It is not a column classification label.';

CREATE INDEX IF NOT EXISTS ix_chunk_metadata_gin ON chunk USING GIN(metadata);
CREATE INDEX IF NOT EXISTS ix_chunk_source_range ON chunk(document_version_id, source_char_start, source_char_end);

CREATE TABLE IF NOT EXISTS chunk_embedding (
  chunk_id UUID NOT NULL REFERENCES chunk(id) ON DELETE CASCADE,
  model_name TEXT NOT NULL,
  model_version TEXT NOT NULL,
  embedding vector(1024) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (chunk_id, model_name, model_version)
);

COMMENT ON TABLE chunk_embedding IS 'BGE-M3 baseline uses 1024 dimensions. Application startup must reject mismatched dimensions.';
CREATE INDEX IF NOT EXISTS ix_chunk_embedding_hnsw
  ON chunk_embedding USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

CREATE TABLE IF NOT EXISTS ingestion_run (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  status TEXT NOT NULL,
  discovered_count INTEGER NOT NULL DEFAULT 0,
  parsed_count INTEGER NOT NULL DEFAULT 0,
  deidentified_count INTEGER NOT NULL DEFAULT 0,
  chunked_count INTEGER NOT NULL DEFAULT 0,
  embedded_count INTEGER NOT NULL DEFAULT 0,
  indexed_count INTEGER NOT NULL DEFAULT 0,
  skipped_count INTEGER NOT NULL DEFAULT 0,
  failed_count INTEGER NOT NULL DEFAULT 0,
  parameters JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS ingestion_item (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  ingestion_run_id UUID NOT NULL REFERENCES ingestion_run(id) ON DELETE CASCADE,
  document_id UUID REFERENCES document(id),
  document_version_id UUID REFERENCES document_version(id),
  source_uri TEXT NOT NULL,
  stage TEXT NOT NULL,
  status TEXT NOT NULL,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  error_code TEXT,
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS quarantine (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  document_id UUID REFERENCES document(id),
  document_version_id UUID REFERENCES document_version(id),
  source_uri TEXT NOT NULL,
  failure_stage TEXT NOT NULL,
  failure_reason TEXT NOT NULL,
  content_hash TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS phi_detection_log (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
  entity_type TEXT NOT NULL,
  entity_count INTEGER NOT NULL CHECK (entity_count >= 0),
  recognizer TEXT NOT NULL,
  policy_version TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE phi_detection_log IS 'PHI detection aggregate metadata only. Raw PHI values are never stored.';
