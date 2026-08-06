package com.medassist.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditEvent(
    UUID eventId,
    Instant timestamp,
    String actor,
    Role role,
    String action,
    String resourceType,
    String resourceId,
    String outcome,
    String payloadHash,
    String previousHash) {
  public AuditEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(resourceType, "resourceType");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(payloadHash, "payloadHash");
  }
}
