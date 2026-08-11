package com.medassist.ingestion.pipeline.model;

/** Pipeline stage that caused a quarantine decision. */
public enum FailureStage {
  NONE,
  PARSE,
  DEIDENTIFICATION
}
