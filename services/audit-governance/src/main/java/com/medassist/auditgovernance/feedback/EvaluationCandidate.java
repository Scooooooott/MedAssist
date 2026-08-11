package com.medassist.auditgovernance.feedback;

import java.util.List;
import java.util.UUID;

/** A review output only; it is not inserted into an evaluation set automatically. */
public record EvaluationCandidate(
    UUID feedbackId,
    String traceId,
    String deidentifiedAnswer,
    List<SupportingSpan> supportingSpans) {
  public EvaluationCandidate {
    if (deidentifiedAnswer == null || deidentifiedAnswer.isBlank()) {
      throw new IllegalArgumentException("deidentifiedAnswer is required");
    }
    supportingSpans = List.copyOf(supportingSpans);
  }
}
