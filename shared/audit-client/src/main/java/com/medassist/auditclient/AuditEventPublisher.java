package com.medassist.auditclient;

/** Service-facing boundary for durable, asynchronous audit publication. */
@FunctionalInterface
public interface AuditEventPublisher {
  /**
   * Persists the event before scheduling delivery, failing closed if persistence is unavailable.
   */
  void publish(SafeAuditEvent event);
}
