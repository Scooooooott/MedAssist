package com.medassist.clinicaldata.persistence;

import java.util.Objects;
import java.util.UUID;

/** Safe import-run outcome; it contains counts and identifiers only. */
public record ClinicalImportPersistenceResult(
    UUID importRunId, int acceptedInsertedCount, int quarantinedInsertedCount) {
  public ClinicalImportPersistenceResult {
    Objects.requireNonNull(importRunId, "importRunId");
    if (acceptedInsertedCount < 0 || quarantinedInsertedCount < 0) {
      throw new IllegalArgumentException("persisted row counts cannot be negative");
    }
  }
}
