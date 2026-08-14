package com.medassist.auditclient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class TestEvents {
  private TestEvents() {}

  public static SafeAuditEvent event() {
    return event(UUID.fromString("3f741f2b-f5a6-4fd6-8b8b-aa21d418a795"));
  }

  public static SafeAuditEvent event(final UUID eventId) {
    return new SafeAuditEvent(
        eventId,
        Instant.parse("2026-01-01T00:00:00.123456789Z"),
        "service-a",
        SafeAuditCategory.DATA_ACCESS,
        "CLINICIAN",
        "READ",
        "CLINICAL_DATA",
        "resource-1",
        "ALLOWED",
        Map.of("entity_count", "2", "policy_version", "policy-v1"));
  }
}
