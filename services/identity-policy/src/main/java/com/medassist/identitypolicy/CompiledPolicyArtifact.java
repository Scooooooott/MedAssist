package com.medassist.identitypolicy;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable shape of compiler output consumed by the in-memory PDP. */
public record CompiledPolicyArtifact(
    String version,
    Set<String> roles,
    Set<String> actions,
    Set<String> resourceTypes,
    List<CompiledPolicyRule> rules) {
  public CompiledPolicyArtifact {
    if (Objects.requireNonNull(version, "version").isBlank()) {
      throw new IllegalArgumentException("policy version is required");
    }
    roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    actions = Set.copyOf(Objects.requireNonNull(actions, "actions"));
    resourceTypes = Set.copyOf(Objects.requireNonNull(resourceTypes, "resourceTypes"));
    rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    validateRules(roles, actions, resourceTypes, rules);
  }

  public CompiledPolicyArtifact(final String version, final List<CompiledPolicyRule> rules) {
    this(
        version,
        values(rules, Dimension.ROLE),
        values(rules, Dimension.ACTION),
        values(rules, Dimension.RESOURCE),
        rules);
  }

  public static CompiledPolicyArtifact fromRules(
      final String version, final List<CompiledPolicyRule> rules) {
    return new CompiledPolicyArtifact(version, rules);
  }

  private static void validateRules(
      final Set<String> roles,
      final Set<String> actions,
      final Set<String> resourceTypes,
      final List<CompiledPolicyRule> rules) {
    for (final CompiledPolicyRule rule : rules) {
      if (!roles.contains(rule.role())
          || !actions.contains(rule.action())
          || !resourceTypes.contains(rule.resourceType())) {
        throw new IllegalArgumentException("policy rule references an undeclared dimension");
      }
    }
  }

  private static Set<String> values(
      final List<CompiledPolicyRule> rules, final Dimension dimension) {
    Objects.requireNonNull(rules, "rules");
    final Set<String> values = new HashSet<>();
    for (final CompiledPolicyRule rule : rules) {
      values.add(dimension.value(rule));
    }
    return values;
  }

  private enum Dimension {
    ROLE {
      @Override
      String value(final CompiledPolicyRule rule) {
        return rule.role();
      }
    },
    ACTION {
      @Override
      String value(final CompiledPolicyRule rule) {
        return rule.action();
      }
    },
    RESOURCE {
      @Override
      String value(final CompiledPolicyRule rule) {
        return rule.resourceType();
      }
    };

    abstract String value(CompiledPolicyRule rule);
  }
}
