package com.medassist.common.context;

/** Base exception for fail-closed context propagation failures. */
public class ContextPropagationException extends IllegalStateException {
  public ContextPropagationException(final String message) {
    super(message);
  }
}
