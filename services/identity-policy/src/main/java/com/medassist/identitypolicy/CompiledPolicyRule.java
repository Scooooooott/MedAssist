package com.medassist.identitypolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One allow rule emitted by the policy compiler. No runtime authorization list is duplicated here.
 */
public record CompiledPolicyRule(
    String role,
    String action,
    String resourceType,
    Map<String, String> requiredAttributes,
    List<Obligation> obligations) {
  public CompiledPolicyRule {
    requireText(role, "role");
    requireText(action, "action");
    requireText(resourceType, "resourceType");
    requiredAttributes =
        Map.copyOf(Objects.requireNonNull(requiredAttributes, "requiredAttributes"));
    obligations = List.copyOf(Objects.requireNonNull(obligations, "obligations"));
  }

  public CompiledPolicyRule(
      final String role,
      final String action,
      final String resourceType,
      final List<Obligation> obligations) {
    this(role, action, resourceType, Map.of(), obligations);
  }

  private static void requireText(final String value, final String field) {
    if (Objects.requireNonNull(value, field).isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
