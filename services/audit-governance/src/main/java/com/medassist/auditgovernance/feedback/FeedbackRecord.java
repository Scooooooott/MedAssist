package com.medassist.auditgovernance.feedback;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FeedbackRecord(
    UUID id, FeedbackSubmission submission, Instant submittedAt, FeedbackStatus status) {
  public FeedbackRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(submission, "submission");
    Objects.requireNonNull(submittedAt, "submittedAt");
    Objects.requireNonNull(status, "status");
  }

  public FeedbackRecord withStatus(final FeedbackStatus nextStatus) {
    return new FeedbackRecord(id, submission, submittedAt, nextStatus);
  }
}
