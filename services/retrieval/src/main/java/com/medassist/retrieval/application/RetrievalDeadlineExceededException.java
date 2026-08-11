package com.medassist.retrieval.application;

public final class RetrievalDeadlineExceededException extends RuntimeException {
  public RetrievalDeadlineExceededException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
