package com.medassist.auditgovernance.feedback;

public final class FeedbackAccessDeniedException extends RuntimeException {
  public FeedbackAccessDeniedException(final String message) {
    super(message);
  }
}
