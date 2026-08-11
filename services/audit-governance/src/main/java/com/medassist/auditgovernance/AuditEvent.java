package com.medassist.auditgovernance;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable, metadata-only audit event. It never accepts a raw payload. */
public record AuditEvent(
    UUID eventId,
    Instant timestamp,
    String actor,
    AuditEventCategory category,
    String role,
    String action,
    String resourceType,
    String resourceId,
    String outcome,
    AuditPayload payload,
    String payloadHash,
    String previousHash,
    String hash) {
  public AuditEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(timestamp, "timestamp");
    requireText(actor, "actor");
    Objects.requireNonNull(category, "category");
    requireText(role, "role");
    requireText(action, "action");
    requireText(resourceType, "resourceType");
    requireText(resourceId, "resourceId");
    requireText(outcome, "outcome");
    payload = Objects.requireNonNull(payload, "payload");
    payloadHash =
        payloadHash == null || payloadHash.isBlank()
            ? CanonicalAuditEventSerializer.hashPayload(payload)
            : payloadHash;
    previousHash = previousHash == null ? "" : previousHash;
    hash = hash == null ? "" : hash;
  }

  public AuditEvent(
      final UUID eventId,
      final Instant timestamp,
      final String actor,
      final String role,
      final String action,
      final String resourceType,
      final String resourceId,
      final String outcome,
      final Map<String, String> payload) {
    this(
        eventId,
        timestamp,
        actor,
        AuditEventClassifier.classify(action, resourceType),
        role,
        action,
        resourceType,
        resourceId,
        outcome,
        AuditPayload.of(payload),
        "",
        "",
        "");
  }

  public AuditEvent(
      final UUID eventId,
      final Instant timestamp,
      final String actor,
      final String role,
      final String action,
      final String resourceType,
      final String resourceId,
      final String outcome,
      final AuditPayload payload) {
    this(
        eventId,
        timestamp,
        actor,
        AuditEventClassifier.classify(action, resourceType),
        role,
        action,
        resourceType,
        resourceId,
        outcome,
        payload,
        "",
        "",
        "");
  }

  public AuditEvent withPreviousHash(final String newPreviousHash) {
    return new AuditEvent(
        eventId,
        timestamp,
        actor,
        category,
        role,
        action,
        resourceType,
        resourceId,
        outcome,
        payload,
        payloadHash,
        newPreviousHash,
        "");
  }

  public AuditEvent withHash(final String newHash) {
    return new AuditEvent(
        eventId,
        timestamp,
        actor,
        category,
        role,
        action,
        resourceType,
        resourceId,
        outcome,
        payload,
        payloadHash,
        previousHash,
        newHash);
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
