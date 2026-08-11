package com.medassist.retrieval.application.model;

/** Deterministic sentence/assertion coverage for a citation validation run. */
public record CitationCoverageResult(
    int assertionCount, int coveredAssertionCount, double minimumCoverage) {
  public CitationCoverageResult {
    if (assertionCount < 0 || coveredAssertionCount < 0) {
      throw new IllegalArgumentException("assertion counts cannot be negative");
    }
    if (coveredAssertionCount > assertionCount) {
      throw new IllegalArgumentException("covered assertions cannot exceed assertions");
    }
    if (!Double.isFinite(minimumCoverage) || minimumCoverage < 0.0d || minimumCoverage > 1.0d) {
      throw new IllegalArgumentException("coverage threshold must be between 0.0 and 1.0");
    }
  }

  public double coverage() {
    return assertionCount == 0 ? 1.0d : (double) coveredAssertionCount / assertionCount;
  }

  public int uncoveredAssertionCount() {
    return assertionCount - coveredAssertionCount;
  }

  public boolean sufficient() {
    return coverage() >= minimumCoverage;
  }
}
