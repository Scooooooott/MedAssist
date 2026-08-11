package com.medassist.ingestion.pipeline.model;

/** Safe-to-publish outcome of parse and de-identification. */
public enum ProcessingStatus {
  SUCCEEDED,
  PARTIAL,
  QUARANTINED
}
