package com.medassist.auditgovernance.transport;

public final class InvalidAuditEventException extends RuntimeException {
  public InvalidAuditEventException(final String message) {
    super(message);
  }

  public InvalidAuditEventException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
