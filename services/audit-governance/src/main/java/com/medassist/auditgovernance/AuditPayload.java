package com.medassist.auditgovernance;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Whitelisted metadata only; raw clinical text and identifiers are intentionally not representable.
 */
public record AuditPayload(Map<String, String> fields) {
  private static final Set<String> SAFE_FIELDS =
      Set.of(
          "requestId",
          "traceId",
          "policyVersion",
          "reasonCode",
          "entityType",
          "entityCount",
          "resultCode",
          "obligation",
          "contentDomain",
          "durationMs");

  public AuditPayload {
    fields = copySafeFields(fields);
  }

  public static AuditPayload empty() {
    return new AuditPayload(Map.of());
  }

  public static AuditPayload of(final Map<String, String> fields) {
    return new AuditPayload(fields);
  }

  public boolean contains(final String field) {
    return fields.containsKey(field);
  }

  private static Map<String, String> copySafeFields(final Map<String, String> values) {
    Objects.requireNonNull(values, "fields");
    final Map<String, String> copy = new TreeMap<>();
    values.forEach(
        (key, value) -> {
          if (!SAFE_FIELDS.contains(key)) {
            throw new IllegalArgumentException("audit payload field is not whitelisted: " + key);
          }
          if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("audit payload value is required: " + key);
          }
          if (value.length() > 256 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("audit payload value is unsafe: " + key);
          }
          copy.put(key, value);
        });
    return Map.copyOf(copy);
  }
}
