package com.medassist.agent.routing;

import com.medassist.agent.state.QueryClassification;
import com.medassist.domain.Role;
import java.util.Objects;
import java.util.Set;

/** Immutable declaration of a tool's role and classification scope. */
public record ToolDefinition(
    String name,
    Set<Role> allowedRoles,
    Set<QueryClassification> supportedClassifications,
    Set<Role> aggregateOnlyRoles) {
  public ToolDefinition {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("tool name must not be blank");
    }
    Objects.requireNonNull(allowedRoles, "allowedRoles");
    Objects.requireNonNull(supportedClassifications, "supportedClassifications");
    Objects.requireNonNull(aggregateOnlyRoles, "aggregateOnlyRoles");
    if (allowedRoles.isEmpty() || supportedClassifications.isEmpty()) {
      throw new IllegalArgumentException("tool scope must not be empty");
    }
    if (!allowedRoles.containsAll(aggregateOnlyRoles)) {
      throw new IllegalArgumentException("aggregate-only roles must be allowed roles");
    }
    allowedRoles = Set.copyOf(allowedRoles);
    supportedClassifications = Set.copyOf(supportedClassifications);
    aggregateOnlyRoles = Set.copyOf(aggregateOnlyRoles);
  }

  public boolean allowedFor(final Role role) {
    return allowedRoles.contains(Objects.requireNonNull(role, "role"));
  }

  public boolean supports(final QueryClassification classification) {
    return supportedClassifications.contains(
        Objects.requireNonNull(classification, "classification"));
  }

  public boolean aggregateOnlyFor(final Role role) {
    return aggregateOnlyRoles.contains(Objects.requireNonNull(role, "role"));
  }

  public boolean aggregateOnly() {
    return !aggregateOnlyRoles.isEmpty();
  }

  public String toolName() {
    return name;
  }
}
