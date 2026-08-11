package com.medassist.clinicaldata.model;

import java.util.Objects;

public record EncounterRecord(
    String encounterId,
    String patientId,
    CodingValue type,
    int startYear,
    Integer endYear,
    CodingValue reasonCode)
    implements ClinicalRecord {
  public EncounterRecord {
    requireText(encounterId, "encounterId");
    requireText(patientId, "patientId");
    Objects.requireNonNull(type, "type");
    if (startYear < 1900 || startYear > 2100) {
      throw new IllegalArgumentException("startYear is outside the supported range");
    }
    if (endYear != null && endYear < startYear) {
      throw new IllegalArgumentException("endYear cannot be before startYear");
    }
  }

  @Override
  public String resourceType() {
    return "Encounter";
  }

  @Override
  public String resourceId() {
    return encounterId;
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
