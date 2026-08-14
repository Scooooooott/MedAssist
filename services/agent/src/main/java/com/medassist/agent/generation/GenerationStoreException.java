package com.medassist.agent.generation;

public final class GenerationStoreException extends RuntimeException {
  public enum Reason {
    IDEMPOTENCY_CONFLICT,
    ACTIVE_LIMIT,
    EVENT_LIMIT,
    BYTE_LIMIT,
    UNAVAILABLE
  }

  private final Reason reason;

  public GenerationStoreException(final Reason reason, final String message) {
    super(message);
    this.reason = reason;
  }

  public GenerationStoreException(
      final Reason reason, final String message, final Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
