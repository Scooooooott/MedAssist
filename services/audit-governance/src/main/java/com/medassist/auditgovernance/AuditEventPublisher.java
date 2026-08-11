package com.medassist.auditgovernance;

public interface AuditEventPublisher {
  AuditEvent publish(AuditEvent event);

  default AuditEvent append(final AuditEvent event) {
    return publish(event);
  }
}
