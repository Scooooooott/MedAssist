package com.medassist.clinicaldata.query;

import java.util.Map;

public record StructuredAggregateRow(Map<String, String> dimensions, long count) {
  public StructuredAggregateRow {
    dimensions = Map.copyOf(dimensions == null ? Map.of() : dimensions);
    if (count < 0) {
      throw new IllegalArgumentException("aggregate count cannot be negative");
    }
    if (dimensions.keySet().stream()
        .map(String::toLowerCase)
        .anyMatch(key -> key.endsWith("_id") || key.equals("id"))) {
      throw new IllegalArgumentException("structured results cannot contain identifiers");
    }
  }
}
