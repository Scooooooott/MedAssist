ALTER TABLE chunk
  ADD COLUMN IF NOT EXISTS context_prefix TEXT,
  ADD COLUMN IF NOT EXISTS contextual_mode TEXT NOT NULL DEFAULT 'OFF'
    CHECK (contextual_mode IN ('OFF', 'RULE_BASED', 'LLM_GENERATED')),
  ADD COLUMN IF NOT EXISTS context_prompt_version TEXT,
  ADD COLUMN IF NOT EXISTS chunking_strategy_id TEXT NOT NULL DEFAULT 'structure-v1'
    CHECK (btrim(chunking_strategy_id) <> '');

ALTER TABLE document_version DROP CONSTRAINT IF EXISTS document_version_status_check;
ALTER TABLE document_version
  ADD CONSTRAINT document_version_status_check
  CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'WITHDRAWN', 'UNKNOWN'));

ALTER TABLE chunk DROP CONSTRAINT IF EXISTS chunk_document_version_id_ordinal_key;

CREATE UNIQUE INDEX IF NOT EXISTS ux_chunk_version_strategy_ordinal
  ON chunk(document_version_id, chunking_strategy_id, ordinal);

ALTER TABLE chunk
  ADD COLUMN IF NOT EXISTS lexical_search TSVECTOR
  GENERATED ALWAYS AS (to_tsvector('english', coalesce(text, ''))) STORED,
  ADD COLUMN IF NOT EXISTS lexical_search_unstemmed TSVECTOR
  GENERATED ALWAYS AS (to_tsvector('simple', coalesce(text, ''))) STORED;

CREATE INDEX IF NOT EXISTS ix_chunk_lexical_search
  ON chunk USING GIN(lexical_search);

CREATE INDEX IF NOT EXISTS ix_chunk_lexical_search_unstemmed
  ON chunk USING GIN(lexical_search_unstemmed);

COMMENT ON COLUMN chunk.text IS
  'Source-faithful de-identified text. Context prefixes must never overwrite this column.';
COMMENT ON COLUMN chunk.context_prefix IS
  'Embedding-only context. Excluded from lexical search, generation input, citations, and display.';
COMMENT ON COLUMN chunk.lexical_search IS
  'Generated exclusively from original chunk.text; never from context_prefix.';
COMMENT ON COLUMN chunk.lexical_search_unstemmed IS
  'Unstemmed control channel generated exclusively from original chunk.text.';

CREATE UNIQUE INDEX IF NOT EXISTS ux_phi_detection_log_identity
  ON phi_detection_log(document_version_id, entity_type, recognizer, policy_version);

ALTER TABLE chunk_embedding
  ADD COLUMN IF NOT EXISTS contextual_mode TEXT NOT NULL DEFAULT 'OFF'
    CHECK (contextual_mode IN ('OFF', 'RULE_BASED', 'LLM_GENERATED'));

ALTER TABLE chunk_embedding DROP CONSTRAINT IF EXISTS chunk_embedding_pkey;
ALTER TABLE chunk_embedding
  ADD PRIMARY KEY (chunk_id, model_name, model_version, contextual_mode);

COMMENT ON TABLE chunk_embedding IS
  'BGE-M3 baseline uses 1024 dimensions. Contextual mode is part of vector identity; application startup must reject mismatched dimensions.';

CREATE TABLE IF NOT EXISTS chunk_context (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  document_version_id UUID NOT NULL,
  chunking_strategy_id TEXT NOT NULL DEFAULT 'structure-v1'
    CHECK (btrim(chunking_strategy_id) <> ''),
  chunk_ordinal INTEGER NOT NULL,
  mode TEXT NOT NULL CHECK (mode IN ('RULE_BASED', 'LLM_GENERATED')),
  prompt_version TEXT NOT NULL,
  context_text TEXT NOT NULL,
  input_tokens INTEGER CHECK (input_tokens IS NULL OR input_tokens >= 0),
  output_tokens INTEGER CHECK (output_tokens IS NULL OR output_tokens >= 0),
  estimated_cost_usd NUMERIC(12, 6)
    CHECK (estimated_cost_usd IS NULL OR estimated_cost_usd >= 0),
  generation_status TEXT NOT NULL CHECK (generation_status IN ('SUCCEEDED', 'RULE_FALLBACK')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_version_id, chunking_strategy_id, chunk_ordinal, mode, prompt_version)
);

COMMENT ON TABLE chunk_context IS
  'Derived embedding context only. It stores no source document, answer, prompt, or PHI original.';
COMMENT ON COLUMN chunk_context.context_text IS
  'Context prefix used only to build an embedding input; the source chunk.text is stored separately.';

CREATE TABLE IF NOT EXISTS document_metadata_review (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
  missing_fields TEXT[] NOT NULL
    CHECK (
      cardinality(missing_fields) > 0
      AND missing_fields <@ ARRAY['effective_date', 'version', 'publisher']::TEXT[]
    ),
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
  reason_code TEXT NOT NULL CHECK (
    reason_code IN (
      'MISSING_EFFECTIVE_DATE',
      'MISSING_VERSION',
      'MISSING_PUBLISHER',
      'PARSE_FAILED',
      'CONFLICTING_METADATA'
    )
  ),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ,
  UNIQUE (document_version_id),
  CHECK (
    (status = 'PENDING' AND resolved_at IS NULL)
    OR (status IN ('CONFIRMED', 'REJECTED') AND resolved_at IS NOT NULL)
  )
);

COMMENT ON TABLE document_metadata_review IS
  'Human review queue for safe metadata fields only; it must not store source text or PHI.';

CREATE TABLE IF NOT EXISTS chunk_phi_review (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  chunk_id UUID NOT NULL REFERENCES chunk(id) ON DELETE CASCADE,
  phi_scan_status TEXT NOT NULL CHECK (phi_scan_status IN ('SUSPECT', 'FAILED')),
  phi_entity_types TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ,
  UNIQUE (chunk_id),
  CHECK (
    (status = 'PENDING' AND resolved_at IS NULL)
    OR (status IN ('CONFIRMED', 'REJECTED') AND resolved_at IS NOT NULL)
  )
);

COMMENT ON TABLE chunk_phi_review IS
  'Review queue for post-de-identification scan outcomes. Stores labels only, never chunk text.';

CREATE TABLE IF NOT EXISTS evaluation_run (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  eval_set_version TEXT NOT NULL,
  split TEXT NOT NULL,
  code_commit TEXT NOT NULL,
  model_name TEXT NOT NULL,
  model_version TEXT NOT NULL,
  judge_model TEXT NOT NULL,
  random_seed BIGINT NOT NULL,
  metrics JSONB NOT NULL CHECK (
    jsonb_typeof(metrics) = 'object'
    AND NOT (
      metrics ?| ARRAY[
        'question', 'answer', 'context', 'source_text', 'chunk_text',
        'prompt', 'response', 'raw_text'
      ]
    )
  ),
  result_uri TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE evaluation_run IS
  'Evaluation metadata and aggregate metrics only. Per-record text, answers, prompts, and PHI are external artifacts.';
COMMENT ON COLUMN evaluation_run.result_uri IS
  'URI to an access-controlled, PHI-free report artifact; raw evaluation records are not stored here.';

CREATE INDEX IF NOT EXISTS ix_evaluation_run_trend
  ON evaluation_run(eval_set_version, model_name, model_version, created_at);

CREATE TABLE IF NOT EXISTS holdout_consumption (
  holdout_version TEXT PRIMARY KEY CHECK (btrim(holdout_version) <> ''),
  eval_set_version TEXT NOT NULL CHECK (btrim(eval_set_version) <> ''),
  status TEXT NOT NULL DEFAULT 'RESERVED' CHECK (status IN ('RESERVED', 'CONSUMED')),
  evaluation_run_id UUID REFERENCES evaluation_run(id),
  reserved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  consumed_at TIMESTAMPTZ,
  use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
  reuse_bias_note TEXT,
  CHECK (
    (status = 'RESERVED'
      AND evaluation_run_id IS NULL
      AND consumed_at IS NULL
      AND use_count = 0)
    OR (status = 'CONSUMED'
      AND evaluation_run_id IS NOT NULL
      AND consumed_at IS NOT NULL
      AND use_count > 0
      AND (use_count = 1 OR reuse_bias_note IS NOT NULL))
  )
);

COMMENT ON TABLE holdout_consumption IS
  'Rolling holdout ledger. A reserved subset is consumed once; any reuse records a count and bias note. No sample text is stored.';
