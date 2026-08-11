package com.medassist.ingestion.context;

/** Raised when the same cache identity is associated with different derived context. */
public final class ContextCacheConflictException extends ContextCacheException {
  public ContextCacheConflictException(final String message) {
    super(message);
  }
}
