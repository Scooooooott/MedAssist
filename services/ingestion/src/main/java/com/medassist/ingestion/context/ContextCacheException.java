package com.medassist.ingestion.context;

/** Safe boundary exception for durable context-cache failures. */
public class ContextCacheException extends RuntimeException {
  public ContextCacheException(final String message) {
    super(message);
  }

  public ContextCacheException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
