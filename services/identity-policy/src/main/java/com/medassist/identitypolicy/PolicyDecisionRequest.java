package com.medassist.identitypolicy;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable PDP input. String dimensions intentionally preserve unknown values for default deny.
 */
public record PolicyDecisionRequest(
    String subject,
    String role,
    String action,
    String resourceType,
    Map<String, String> attributes) {
  public PolicyDecisionRequest {
    requireText(subject, "subject");
    requireText(role, "role");
    requireText(action, "action");
    requireText(resourceType, "resourceType");
    attributes = copyAttributes(attributes);
  }

  public PolicyDecisionRequest(
      final String subject,
      final Role role,
      final Action action,
      final ResourceType resourceType,
      final Map<String, String> attributes) {
    this(subject, role.name(), action.name(), resourceType.name(), attributes);
  }

  public static PolicyDecisionRequest of(
      final String subject,
      final String role,
      final String action,
      final String resourceType,
      final Map<String, String> attributes) {
    return new PolicyDecisionRequest(subject, role, action, resourceType, attributes);
  }

  private static Map<String, String> copyAttributes(final Map<String, String> values) {
    Objects.requireNonNull(values, "attributes");
    values.forEach(
        (key, value) -> {
          requireText(key, "attribute key");
          Objects.requireNonNull(value, "attribute value");
        });
    return Map.copyOf(values);
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
