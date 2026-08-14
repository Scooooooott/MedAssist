package com.medassist.auditclient.outbox;

/** Signals fail-closed publication when no additional durable audit event can be accepted. */
public final class AuditOutboxFullException extends IllegalStateException {
  public AuditOutboxFullException(final String message) {
    super(message);
  }
}
