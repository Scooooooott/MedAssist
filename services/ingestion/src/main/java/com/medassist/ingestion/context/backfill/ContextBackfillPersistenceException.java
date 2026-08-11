package com.medassist.ingestion.context.backfill;

/** Safe context backfill persistence failure with no source text attached. */
public final class ContextBackfillPersistenceException extends RuntimeException {
  public ContextBackfillPersistenceException(final String message) {
    super(message);
  }

  public ContextBackfillPersistenceException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
