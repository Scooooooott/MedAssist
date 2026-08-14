package com.medassist.auditgovernance.transport;

public final class AuditBufferFullException extends RuntimeException {
  public AuditBufferFullException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
