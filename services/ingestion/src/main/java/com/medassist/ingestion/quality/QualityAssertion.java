package com.medassist.ingestion.quality;

import java.util.Objects;

/** Declaration of a quality rule; its numeric threshold is supplied separately by config. */
public record QualityAssertion(
    String code, String description, AssertionSeverity severity, QualityMetric metric) {
  public QualityAssertion {
    requireText(code, "code");
    requireText(description, "description");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(metric, "metric");
  }

  private static void requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
