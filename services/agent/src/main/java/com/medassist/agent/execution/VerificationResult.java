package com.medassist.agent.execution;

import com.medassist.agent.state.CitationSummary;
import java.util.Objects;

public record VerificationResult(
    boolean accepted, boolean retryable, CitationSummary citationSummary) {
  public VerificationResult {
    Objects.requireNonNull(citationSummary, "citationSummary");
    if (accepted && retryable) {
      throw new IllegalArgumentException("accepted result cannot be retryable");
    }
  }

  public static VerificationResult accepted(final CitationSummary citationSummary) {
    return new VerificationResult(true, false, citationSummary);
  }

  public static VerificationResult retry(final CitationSummary citationSummary) {
    return new VerificationResult(false, true, citationSummary);
  }

  public static VerificationResult reject(final CitationSummary citationSummary) {
    return new VerificationResult(false, false, citationSummary);
  }
}
