package com.medassist.common.context;

/** Raised when asynchronous work has no authenticated execution context. */
public final class MissingExecutionContextException extends ContextPropagationException {
  public MissingExecutionContextException(final String message) {
    super(message);
  }
}
