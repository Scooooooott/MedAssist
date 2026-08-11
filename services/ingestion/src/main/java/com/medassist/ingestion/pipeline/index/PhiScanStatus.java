package com.medassist.ingestion.pipeline.index;

/** PHI scan outcome attached to every publishable chunk. */
public enum PhiScanStatus {
  CLEAN,
  SUSPECT,
  FAILED
}
