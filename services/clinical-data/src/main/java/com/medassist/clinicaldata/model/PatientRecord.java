package com.medassist.clinicaldata.model;

import java.util.Objects;

public record PatientRecord(
    String patientId,
    int birthYear,
    String ageBand,
    String gender,
    String race,
    String ethnicity,
    String zip3)
    implements ClinicalRecord {
  public PatientRecord {
    requireText(patientId, "patientId");
    if (birthYear < 1900 || birthYear > 2100) {
      throw new IllegalArgumentException("birthYear is outside the supported range");
    }
    requireText(ageBand, "ageBand");
    requireText(gender, "gender");
    if (zip3 != null && !zip3.matches("\\d{3}")) {
      throw new IllegalArgumentException("zip3 must contain exactly three digits");
    }
  }

  @Override
  public String resourceType() {
    return "Patient";
  }

  @Override
  public String resourceId() {
    return patientId;
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
