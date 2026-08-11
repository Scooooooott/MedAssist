package com.medassist.agent.execution;

import java.util.Objects;

/** A scalar aggregation output; row-level or document content is not representable here. */
public record SafeAggregationColumn(String name, String value) {
  public SafeAggregationColumn {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("aggregation column name must not be blank");
    }
    Objects.requireNonNull(value, "value");
  }
}
