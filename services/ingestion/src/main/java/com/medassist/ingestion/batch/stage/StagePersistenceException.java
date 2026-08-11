package com.medassist.ingestion.batch.stage;

public final class StagePersistenceException extends RuntimeException {
  public StagePersistenceException(final String message) {
    super(message);
  }
}
