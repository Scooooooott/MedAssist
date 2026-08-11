package com.medassist.ingestion.quality;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Safe comparison of aggregate assertion outcomes with a previous batch. */
public record QualityTrendComparison(
    String previousBatchId,
    Map<String, Double> metricDeltas,
    Set<String> newlyFailedAssertions,
    Set<String> recoveredAssertions) {
  public QualityTrendComparison {
    if (previousBatchId == null || previousBatchId.isBlank()) {
      throw new IllegalArgumentException("previousBatchId must not be blank");
    }
    Objects.requireNonNull(metricDeltas, "metricDeltas");
    if (metricDeltas.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey() == null
                    || entry.getValue() == null
                    || !Double.isFinite(entry.getValue()))) {
      throw new IllegalArgumentException("metric deltas must be finite and keyed");
    }
    metricDeltas = Map.copyOf(metricDeltas);
    newlyFailedAssertions =
        Set.copyOf(Objects.requireNonNull(newlyFailedAssertions, "newlyFailedAssertions"));
    recoveredAssertions =
        Set.copyOf(Objects.requireNonNull(recoveredAssertions, "recoveredAssertions"));
  }
}
