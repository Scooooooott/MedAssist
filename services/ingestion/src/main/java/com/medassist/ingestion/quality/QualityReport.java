package com.medassist.ingestion.quality;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete PHI-safe quality decision and retained warning evidence for one batch. */
public record QualityReport(
    String batchId,
    QualitySnapshot snapshot,
    List<AssertionResult> results,
    Optional<BatchRejection> rejection,
    Optional<QualityTrendComparison> trend) {
  public QualityReport {
    if (batchId == null || batchId.isBlank()) {
      throw new IllegalArgumentException("batchId must not be blank");
    }
    Objects.requireNonNull(snapshot, "snapshot");
    results = List.copyOf(Objects.requireNonNull(results, "results"));
    rejection = Objects.requireNonNull(rejection, "rejection");
    trend = Objects.requireNonNull(trend, "trend");
    if (rejection.isEmpty()
        && results.stream()
            .anyMatch(
                result -> result.severity() == AssertionSeverity.BLOCKING && !result.passed())) {
      throw new IllegalArgumentException("blocking failures require a batch rejection");
    }
  }

  public boolean accepted() {
    return rejection.isEmpty();
  }

  public List<AssertionResult> warnings() {
    return results.stream()
        .filter(result -> result.severity() == AssertionSeverity.WARNING)
        .toList();
  }
}
