package com.medassist.ingestion.context;

/** Raised when LLM contextual retrieval lacks approved cost evidence. */
public final class ContextCostGateException extends RuntimeException {
  public ContextCostGateException(final String message) {
    super(message);
  }
}
