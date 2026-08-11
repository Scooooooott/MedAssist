CREATE TABLE IF NOT EXISTS chunk_embedding_768 (
  chunk_id UUID NOT NULL REFERENCES chunk(id) ON DELETE CASCADE,
  model_name TEXT NOT NULL,
  model_version TEXT NOT NULL,
  contextual_mode TEXT NOT NULL DEFAULT 'OFF'
    CHECK (contextual_mode IN ('OFF', 'RULE_BASED', 'LLM_GENERATED')),
  embedding vector(768) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (chunk_id, model_name, model_version, contextual_mode)
);

CREATE INDEX IF NOT EXISTS ix_chunk_embedding_768_hnsw
  ON chunk_embedding_768 USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS ix_chunk_embedding_768_identity
  ON chunk_embedding_768(model_name, model_version, contextual_mode);

CREATE TABLE IF NOT EXISTS chunk_embedding_1536 (
  chunk_id UUID NOT NULL REFERENCES chunk(id) ON DELETE CASCADE,
  model_name TEXT NOT NULL,
  model_version TEXT NOT NULL,
  contextual_mode TEXT NOT NULL DEFAULT 'OFF'
    CHECK (contextual_mode IN ('OFF', 'RULE_BASED', 'LLM_GENERATED')),
  embedding vector(1536) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (chunk_id, model_name, model_version, contextual_mode)
);

CREATE INDEX IF NOT EXISTS ix_chunk_embedding_1536_hnsw
  ON chunk_embedding_1536 USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS ix_chunk_embedding_1536_identity
  ON chunk_embedding_1536(model_name, model_version, contextual_mode);

CREATE TABLE IF NOT EXISTS embedding_model_registry (
  model_name TEXT NOT NULL,
  model_version TEXT NOT NULL,
  dimension INTEGER NOT NULL CHECK (dimension IN (768, 1024, 1536)),
  provider_type TEXT NOT NULL CHECK (provider_type IN ('ONNX_LOCAL', 'API')),
  enabled BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (model_name, model_version)
);

COMMENT ON TABLE embedding_model_registry IS
  'One immutable model identity maps to one declared vector dimension; dimension-specific indexes remain isolated.';
COMMENT ON COLUMN embedding_model_registry.dimension IS
  'Must match the vector table selected by model routing: 768, 1024 baseline, or 1536.';

COMMENT ON TABLE chunk_embedding_768 IS
  'Dimension-isolated M2 experiment index. It does not replace or mutate the 1024-dimensional baseline.';
COMMENT ON TABLE chunk_embedding_1536 IS
  'Dimension-isolated M2 experiment index. It does not replace or mutate the 1024-dimensional baseline.';
