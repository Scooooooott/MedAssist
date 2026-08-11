package com.medassist.clinicaldata.model;

import java.util.Objects;

public record ConditionRecord(
    String conditionId,
    String patientId,
    String encounterId,
    CodingValue code,
    String display,
    Integer onsetYear,
    String status)
    implements ClinicalRecord {
  public ConditionRecord {
    requireText(conditionId, "conditionId");
    requireText(patientId, "patientId");
    Objects.requireNonNull(code, "code");
    requireText(status, "status");
    if (onsetYear != null && (onsetYear < 1900 || onsetYear > 2100)) {
      throw new IllegalArgumentException("onsetYear is outside the supported range");
    }
  }

  @Override
  public String resourceType() {
    return "Condition";
  }

  @Override
  public String resourceId() {
    return conditionId;
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
