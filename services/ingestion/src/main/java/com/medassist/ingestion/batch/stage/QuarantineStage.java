package com.medassist.ingestion.batch.stage;

public enum QuarantineStage {
  DISCOVERY,
  PARSE,
  DEIDENTIFICATION,
  PHI_SCAN,
  CHUNKING,
  EMBEDDING,
  INDEXING,
  PERSISTENCE
}
