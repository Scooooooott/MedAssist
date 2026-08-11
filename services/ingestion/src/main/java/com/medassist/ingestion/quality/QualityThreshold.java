package com.medassist.ingestion.quality;

import java.util.Objects;

/** One configured, direction-aware threshold. */
public record QualityThreshold(ThresholdComparison comparison, double value) {
  public QualityThreshold {
    Objects.requireNonNull(comparison, "comparison");
    if (!Double.isFinite(value) || value < 0) {
      throw new IllegalArgumentException("threshold value must be finite and non-negative");
    }
  }

  boolean accepts(final double actual) {
    return comparison.accepts(actual, value);
  }
}
