package com.medassist.common.context;

/** Raised when a pooled worker is entered with context left by earlier work. */
public final class ResidualContextException extends ContextPropagationException {
  public ResidualContextException(final String message) {
    super(message);
  }
}
