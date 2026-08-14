package com.medassist.common.context;

/** Raised when an authenticated context cannot be mapped to a safe request identity. */
public final class InvalidExecutionContextException extends ContextPropagationException {
  public InvalidExecutionContextException(final String message) {
    super(message);
  }
}
