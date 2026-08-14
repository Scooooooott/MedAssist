package com.medassist.agent.generation;

public final class GenerationException extends RuntimeException {
  public enum Reason {
    INVALID_REQUEST,
    NOT_FOUND,
    FORBIDDEN,
    EXPIRED,
    TERMINAL_CONFLICT
  }

  private final Reason reason;

  public GenerationException(final Reason reason, final String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
