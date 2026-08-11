package com.medassist.ingestion.quality;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/** PHI-safe outcome of one assertion. */
public record AssertionResult(
    String assertionCode,
    AssertionSeverity severity,
    boolean passed,
    double actualValue,
    OptionalDouble thresholdValue,
    Set<String> entityTypes,
    Set<String> contentHashes,
    String message) {
  public AssertionResult {
    if (assertionCode == null || assertionCode.isBlank()) {
      throw new IllegalArgumentException("assertionCode must not be blank");
    }
    Objects.requireNonNull(severity, "severity");
    if (!Double.isFinite(actualValue)) {
      throw new IllegalArgumentException("actualValue must be finite");
    }
    Objects.requireNonNull(thresholdValue, "thresholdValue");
    entityTypes = Set.copyOf(Objects.requireNonNull(entityTypes, "entityTypes"));
    contentHashes = Set.copyOf(Objects.requireNonNull(contentHashes, "contentHashes"));
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
  }
}
