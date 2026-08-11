package com.medassist.clinicaldata.model;

import java.util.Objects;

public record MedicationRecord(
    String medicationId,
    String patientId,
    String encounterId,
    CodingValue code,
    String display,
    Integer startYear,
    Integer endYear,
    String status)
    implements ClinicalRecord {
  public MedicationRecord {
    requireText(medicationId, "medicationId");
    requireText(patientId, "patientId");
    Objects.requireNonNull(code, "code");
    requireText(status, "status");
    if (startYear != null && (startYear < 1900 || startYear > 2100)) {
      throw new IllegalArgumentException("startYear is outside the supported range");
    }
    if (endYear != null && startYear != null && endYear < startYear) {
      throw new IllegalArgumentException("endYear cannot be before startYear");
    }
  }

  @Override
  public String resourceType() {
    return "Medication";
  }

  @Override
  public String resourceId() {
    return medicationId;
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
