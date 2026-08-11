package com.medassist.ingestion.quality;

/** Whether a failed assertion rejects a batch or is retained as a warning. */
public enum AssertionSeverity {
  BLOCKING,
  WARNING
}
