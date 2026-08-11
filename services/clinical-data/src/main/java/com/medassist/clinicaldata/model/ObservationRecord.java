package com.medassist.clinicaldata.model;

import java.util.Objects;

public record ObservationRecord(
    String observationId,
    String patientId,
    String encounterId,
    CodingValue code,
    String display,
    String value,
    String unit,
    int observationYear)
    implements ClinicalRecord {
  public ObservationRecord {
    requireText(observationId, "observationId");
    requireText(patientId, "patientId");
    Objects.requireNonNull(code, "code");
    requireText(value, "value");
    if (observationYear < 1900 || observationYear > 2100) {
      throw new IllegalArgumentException("observationYear is outside the supported range");
    }
  }

  @Override
  public String resourceType() {
    return "Observation";
  }

  @Override
  public String resourceId() {
    return observationId;
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
