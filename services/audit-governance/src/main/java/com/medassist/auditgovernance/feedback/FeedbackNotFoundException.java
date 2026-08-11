package com.medassist.auditgovernance.feedback;

public final class FeedbackNotFoundException extends RuntimeException {
  public FeedbackNotFoundException(final String message) {
    super(message);
  }
}
