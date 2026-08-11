package com.medassist.clinicaldata.research;

import java.util.Map;

/** An aggregate row contains dimensions and a count, never a patient or encounter id. */
public record ResearchAggregateRow(Map<String, String> dimensions, long patientCount) {
  public ResearchAggregateRow {
    dimensions = Map.copyOf(dimensions == null ? Map.of() : dimensions);
    if (patientCount < 0) {
      throw new IllegalArgumentException("patientCount cannot be negative");
    }
    if (dimensions.keySet().stream()
        .map(String::toLowerCase)
        .anyMatch(key -> key.endsWith("_id") || key.equals("id"))) {
      throw new IllegalArgumentException("research dimensions cannot contain identifiers");
    }
  }
}
