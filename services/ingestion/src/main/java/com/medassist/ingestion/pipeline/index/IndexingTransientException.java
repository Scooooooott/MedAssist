package com.medassist.ingestion.pipeline.index;

/** Retryable indexing failure caused by a transient downstream dependency. */
public final class IndexingTransientException extends RuntimeException {
  public IndexingTransientException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
