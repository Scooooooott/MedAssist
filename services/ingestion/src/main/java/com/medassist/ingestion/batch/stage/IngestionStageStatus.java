package com.medassist.ingestion.batch.stage;

public enum IngestionStageStatus {
  DISCOVERED,
  DEIDENTIFIED,
  INDEX_READY,
  INDEXED,
  QUARANTINED
}
