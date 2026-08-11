package com.medassist.agent.trajectory;

import com.medassist.agent.state.CitationSummary;
import java.io.Serializable;
import java.util.Objects;

/** Safe citation coverage projection for trajectory evaluation. */
public record CitationCoverage(int candidateCount, int validCount, boolean sufficientEvidence)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public CitationCoverage {
    if (candidateCount < 0 || validCount < 0 || validCount > candidateCount) {
      throw new IllegalArgumentException("invalid citation coverage counts");
    }
  }

  public static CitationCoverage from(final CitationSummary summary) {
    Objects.requireNonNull(summary, "summary");
    return new CitationCoverage(
        summary.candidateCount(), summary.validCount(), summary.sufficientEvidence());
  }

  public static CitationCoverage empty() {
    return new CitationCoverage(0, 0, false);
  }

  /** Returns zero for an empty citation denominator instead of NaN. */
  public double ratio() {
    return candidateCount == 0 ? 0.0 : (double) validCount / candidateCount;
  }

  public double coverage() {
    return ratio();
  }
}
