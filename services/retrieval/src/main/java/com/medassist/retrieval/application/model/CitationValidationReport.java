package com.medassist.retrieval.application.model;

import java.util.List;
import java.util.Objects;

/** Full citation and coverage decision; the legacy per-citation result remains unchanged. */
public record CitationValidationReport(
    List<CitationValidationResult> citationResults,
    CitationCoverageResult coverage,
    CitationValidationStatus status,
    List<String> freshnessWarnings) {
  public CitationValidationReport {
    citationResults = List.copyOf(Objects.requireNonNull(citationResults, "citationResults"));
    coverage = Objects.requireNonNull(coverage, "coverage");
    status = Objects.requireNonNull(status, "status");
    freshnessWarnings = List.copyOf(Objects.requireNonNull(freshnessWarnings, "freshnessWarnings"));
  }

  public boolean hasInvalidCitations() {
    return citationResults.stream().anyMatch(result -> !result.valid());
  }

  public long invalidCitationCount() {
    return citationResults.stream().filter(result -> !result.valid()).count();
  }

  public boolean insufficientCoverage() {
    return status == CitationValidationStatus.INSUFFICIENT_COVERAGE;
  }

  public boolean valid() {
    return status == CitationValidationStatus.VALID;
  }
}
