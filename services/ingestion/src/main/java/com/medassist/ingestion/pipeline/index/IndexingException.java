package com.medassist.ingestion.pipeline.index;

/** Fail-closed application error; callers must quarantine the document and persist no records. */
public final class IndexingException extends RuntimeException {
  public IndexingException(final String message) {
    super(message);
  }
}
