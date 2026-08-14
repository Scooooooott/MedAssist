package com.medassist.auditgovernance.transport;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryProcessedAuditEventStore implements ProcessedAuditEventStore {
  private final Set<UUID> eventIds = ConcurrentHashMap.newKeySet();

  @Override
  public boolean contains(final UUID eventId) {
    return eventIds.contains(eventId);
  }

  @Override
  public void markProcessed(final UUID eventId) {
    eventIds.add(eventId);
  }
}
