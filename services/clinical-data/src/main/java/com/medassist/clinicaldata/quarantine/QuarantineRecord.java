package com.medassist.clinicaldata.quarantine;

import java.util.Objects;

/** Safe failure metadata only. The raw FHIR payload is deliberately not retained. */
public record QuarantineRecord(
    String sourceId,
    String resourceType,
    String resourceId,
    QuarantineStage stage,
    QuarantineReason reasonCode,
    String reason) {
  public QuarantineRecord {
    requireText(sourceId, "sourceId");
    requireText(resourceType, "resourceType");
    requireText(resourceId, "resourceId");
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(reasonCode, "reasonCode");
    requireText(reason, "reason");
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
