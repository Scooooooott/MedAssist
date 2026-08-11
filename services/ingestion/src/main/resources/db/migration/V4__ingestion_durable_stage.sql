CREATE TABLE IF NOT EXISTS ingestion_stage (
  ingestion_run_id UUID NOT NULL REFERENCES ingestion_run(id) ON DELETE CASCADE,
  logical_document_id UUID NOT NULL,
  document_version_id UUID NOT NULL,
  source_uri TEXT NOT NULL CHECK (btrim(source_uri) <> ''),
  source_id TEXT NOT NULL CHECK (btrim(source_id) <> ''),
  mime_type TEXT NOT NULL CHECK (btrim(mime_type) <> ''),
  size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
  content_hash TEXT NOT NULL CHECK (btrim(content_hash) <> ''),
  previous_content_hash TEXT,
  classification TEXT NOT NULL CHECK (classification IN ('NEW', 'CHANGED', 'UNCHANGED')),
  object_metadata JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(object_metadata) = 'object'),
  force_reprocess BOOLEAN NOT NULL DEFAULT false,
  status TEXT NOT NULL CHECK (
    status IN ('DISCOVERED', 'DEIDENTIFIED', 'INDEX_READY', 'INDEXED', 'QUARANTINED')
  ),
  deidentified_ir JSONB,
  phi_type_counts JSONB,
  policy_version TEXT,
  processing_status TEXT,
  indexing_result JSONB,
  quarantine_stage TEXT,
  error_code TEXT,
  safe_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (ingestion_run_id, document_version_id),
  CHECK (previous_content_hash IS NULL OR btrim(previous_content_hash) <> ''),
  CHECK (deidentified_ir IS NULL OR jsonb_typeof(deidentified_ir) = 'object'),
  CHECK (phi_type_counts IS NULL OR jsonb_typeof(phi_type_counts) = 'object'),
  CHECK (policy_version IS NULL OR btrim(policy_version) <> ''),
  CHECK (processing_status IS NULL OR processing_status IN ('SUCCEEDED', 'PARTIAL')),
  CHECK (indexing_result IS NULL OR jsonb_typeof(indexing_result) = 'object'),
  CHECK (
    quarantine_stage IS NULL
    OR quarantine_stage IN (
      'DISCOVERY', 'PARSE', 'DEIDENTIFICATION', 'PHI_SCAN',
      'CHUNKING', 'EMBEDDING', 'INDEXING', 'PERSISTENCE'
    )
  ),
  CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  CHECK (
    safe_reason IS NULL
    OR (
      btrim(safe_reason) <> ''
      AND char_length(safe_reason) <= 256
      AND position(chr(10) IN safe_reason) = 0
      AND position(chr(13) IN safe_reason) = 0
    )
  ),
  CHECK (
    (status = 'DISCOVERED' AND deidentified_ir IS NULL AND phi_type_counts IS NULL
      AND policy_version IS NULL AND processing_status IS NULL AND indexing_result IS NULL)
    OR (status = 'DEIDENTIFIED' AND deidentified_ir IS NOT NULL AND phi_type_counts IS NOT NULL
      AND policy_version IS NOT NULL AND processing_status IS NOT NULL AND indexing_result IS NULL)
    OR (status IN ('INDEX_READY', 'INDEXED') AND deidentified_ir IS NOT NULL
      AND phi_type_counts IS NOT NULL AND policy_version IS NOT NULL
      AND processing_status IS NOT NULL AND indexing_result IS NOT NULL)
    OR status = 'QUARANTINED'
  ),
  CHECK (
    (status = 'QUARANTINED'
      AND quarantine_stage IS NOT NULL
      AND error_code IS NOT NULL
      AND safe_reason IS NOT NULL)
    OR (status <> 'QUARANTINED'
      AND quarantine_stage IS NULL
      AND error_code IS NULL
      AND safe_reason IS NULL)
  )
);

CREATE INDEX IF NOT EXISTS ix_ingestion_stage_run_status_order
  ON ingestion_stage(ingestion_run_id, status, source_id, document_version_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quarantine_safe_failure_identity
  ON quarantine(source_uri, content_hash, failure_stage, failure_reason);

COMMENT ON TABLE ingestion_stage IS
  'Restart-safe ingestion state. Only safe discovery metadata, de-identified IR, and final indexing payload may be stored.';
COMMENT ON COLUMN ingestion_stage.deidentified_ir IS
  'De-identified parser IR only. Raw/original parser IR and PHI values are prohibited.';
COMMENT ON COLUMN ingestion_stage.indexing_result IS
  'Final de-identified chunks and embedding payload required to resume indexing.';
COMMENT ON COLUMN ingestion_stage.safe_reason IS
  'Bounded, single-line operational reason. Never store exception messages or stack traces.';
