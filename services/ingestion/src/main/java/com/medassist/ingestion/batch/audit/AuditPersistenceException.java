package com.medassist.ingestion.batch.audit;

/** Content-free persistence failure exposed by the audit boundary. */
public final class AuditPersistenceException extends RuntimeException {
  public AuditPersistenceException(final String message) {
    super(message);
  }
}
