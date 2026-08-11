package com.medassist.ingestion.pipeline.store;

/** Fail-closed persistence error; callers must quarantine the document. */
public final class IndexingPersistenceException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public IndexingPersistenceException(final String message) {
    super(message);
  }

  public IndexingPersistenceException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
