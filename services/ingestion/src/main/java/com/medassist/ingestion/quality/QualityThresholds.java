package com.medassist.ingestion.quality;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable configuration object containing every quality threshold. */
public record QualityThresholds(Map<String, QualityThreshold> values) {
  public QualityThresholds {
    Objects.requireNonNull(values, "values");
    if (values.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null)) {
      throw new IllegalArgumentException("quality thresholds must have non-blank keys and values");
    }
    values = Map.copyOf(values);
  }

  public Optional<QualityThreshold> thresholdFor(final String assertionCode) {
    return Optional.ofNullable(values.get(assertionCode));
  }
}
