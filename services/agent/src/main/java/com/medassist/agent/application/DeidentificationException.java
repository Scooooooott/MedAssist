package com.medassist.agent.application;

public final class DeidentificationException extends RuntimeException {
  public DeidentificationException(final String message) {
    super(message);
  }

  public DeidentificationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
