package com.medassist.auditgovernance.feedback;

import java.util.List;
import java.util.Objects;

/** Structured feedback deliberately has no free-text field, avoiding a second PHI ingress path. */
public record FeedbackSubmission(
    String traceId,
    FeedbackOverallRating overallRating,
    List<CitationFeedback> citationRatings,
    FeedbackIssueCategory issueCategory,
    FeedbackSeverity severity) {
  public FeedbackSubmission {
    if (traceId == null || traceId.isBlank() || traceId.length() > 128) {
      throw new IllegalArgumentException("traceId is required and must be bounded");
    }
    if (!traceId.matches("[A-Za-z0-9._:-]+")) {
      throw new IllegalArgumentException("traceId contains unsupported characters");
    }
    Objects.requireNonNull(overallRating, "overallRating");
    citationRatings = List.copyOf(Objects.requireNonNull(citationRatings, "citationRatings"));
    Objects.requireNonNull(issueCategory, "issueCategory");
    Objects.requireNonNull(severity, "severity");
  }
}
