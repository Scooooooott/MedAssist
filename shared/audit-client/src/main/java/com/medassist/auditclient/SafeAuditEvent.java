package com.medassist.auditclient;

import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable metadata-only event safe to cross the shared audit transport boundary. */
public record SafeAuditEvent(
    UUID eventId,
    Instant occurredAt,
    String actor,
    SafeAuditCategory category,
    String role,
    String action,
    String resourceType,
    String resourceId,
    String outcome,
    Map<String, String> safeMetadata) {
  private static final int MAX_IDENTITY_LENGTH = 256;

  public SafeAuditEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    actor = requireSafeText(actor, "actor");
    Objects.requireNonNull(category, "category");
    role = requireSafeText(role, "role");
    action = requireSafeText(action, "action");
    resourceType = requireSafeText(resourceType, "resourceType");
    resourceId = requireSafeText(resourceId, "resourceId");
    outcome = requireSafeText(outcome, "outcome");
    safeMetadata = SafeAuditMetadata.sanitize(safeMetadata);
  }

  /** Creates an event from explicit authenticated identity and domain fields. */
  public static SafeAuditEvent create(
      final String actor,
      final SafeAuditCategory category,
      final String role,
      final String action,
      final String resourceType,
      final String resourceId,
      final String outcome,
      final Map<String, String> safeMetadata) {
    return new SafeAuditEvent(
        UUID.randomUUID(),
        Instant.now(),
        actor,
        category,
        role,
        action,
        resourceType,
        resourceId,
        outcome,
        safeMetadata);
  }

  /** Creates an event from the authenticated thread context and adds safe correlation metadata. */
  public static SafeAuditEvent fromCurrentContext(
      final SafeAuditCategory category,
      final String action,
      final String resourceType,
      final String resourceId,
      final String outcome,
      final Map<String, String> safeMetadata) {
    final ExecutionContext context = ContextCarrier.requireCurrent();
    if (context.roles().size() != 1) {
      throw new IllegalStateException("exactly one authenticated role is required for audit");
    }
    if (safeMetadata.containsKey("trace_id") || safeMetadata.containsKey("request_id")) {
      throw new IllegalArgumentException("context correlation metadata cannot be overridden");
    }
    final Map<String, String> correlatedMetadata = new HashMap<>(safeMetadata);
    correlatedMetadata.put("trace_id", context.traceId());
    correlatedMetadata.put("request_id", context.requestId());
    return create(
        context.subject(),
        category,
        context.roles().iterator().next(),
        action,
        resourceType,
        resourceId,
        outcome,
        correlatedMetadata);
  }

  private static String requireSafeText(final String value, final String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    if (value.length() > MAX_IDENTITY_LENGTH
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(field + " is unsafe");
    }
    return value;
  }
}
