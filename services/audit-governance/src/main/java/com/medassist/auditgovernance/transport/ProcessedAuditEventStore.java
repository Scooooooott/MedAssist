package com.medassist.auditgovernance.transport;

import java.util.UUID;

public interface ProcessedAuditEventStore {
  boolean contains(UUID eventId);

  void markProcessed(UUID eventId);
}
