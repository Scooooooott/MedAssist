package com.medassist.ingestion.pipeline.parse;

/** Parser outcome reported by the sidecar. */
public enum ParseStatus {
  SUCCEEDED,
  PARTIAL,
  FAILED
}
