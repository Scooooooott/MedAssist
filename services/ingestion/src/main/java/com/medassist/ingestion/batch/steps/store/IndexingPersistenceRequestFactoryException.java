package com.medassist.ingestion.batch.steps.store;

/** A fixed, safe failure raised when a stage item cannot produce a publish request. */
public final class IndexingPersistenceRequestFactoryException extends RuntimeException {
  private final Failure failure;

  public IndexingPersistenceRequestFactoryException(final Failure failure) {
    super(failure.safeReason());
    this.failure = failure;
  }

  public Failure failure() {
    return failure;
  }

  public enum Failure {
    INVALID_REQUEST("INDEXING_REQUEST_INVALID", "indexing request validation failed");

    private final String errorCode;
    private final String safeReason;

    Failure(final String errorCode, final String safeReason) {
      this.errorCode = errorCode;
      this.safeReason = safeReason;
    }

    public String errorCode() {
      return errorCode;
    }

    public String safeReason() {
      return safeReason;
    }
  }
}
