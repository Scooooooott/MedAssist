package com.medassist.identitypolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure in-memory PDP. A missing, invalid, or failing policy source can only produce deny. */
public class FailClosedPolicyDecisionPoint implements PolicyDecisionPoint {
  private final CompiledPolicyArtifact artifact;

  public FailClosedPolicyDecisionPoint(final CompiledPolicyArtifact artifact) {
    this.artifact = artifact;
  }

  public FailClosedPolicyDecisionPoint(final CompiledPolicySource source) {
    this.artifact = readOrNull(source);
  }

  @Override
  public PolicyDecisionResponse decide(final PolicyDecisionRequest request) {
    try {
      if (artifact == null || request == null || !declaredDimensions(request)) {
        return PolicyDecisionResponse.deny("policy is unavailable or request dimension is unknown");
      }
      for (final CompiledPolicyRule rule : artifact.rules()) {
        if (matches(rule, request)) {
          return PolicyDecisionResponse.allow(
              "matched compiled policy " + artifact.version(), rule.obligations());
        }
      }
      return PolicyDecisionResponse.deny("no compiled policy rule matched");
    } catch (final RuntimeException exception) {
      return PolicyDecisionResponse.deny("policy decision failed closed");
    }
  }

  private boolean declaredDimensions(final PolicyDecisionRequest request) {
    return artifact.roles().contains(request.role())
        && artifact.actions().contains(request.action())
        && artifact.resourceTypes().contains(request.resourceType());
  }

  private boolean matches(final CompiledPolicyRule rule, final PolicyDecisionRequest request) {
    return rule.role().equals(request.role())
        && rule.action().equals(request.action())
        && rule.resourceType().equals(request.resourceType())
        && containsAttributes(request.attributes(), rule.requiredAttributes());
  }

  private boolean containsAttributes(
      final Map<String, String> actual, final Map<String, String> required) {
    return required.entrySet().stream()
        .allMatch(entry -> Objects.equals(actual.get(entry.getKey()), entry.getValue()));
  }

  private static CompiledPolicyArtifact readOrNull(final CompiledPolicySource source) {
    try {
      return source == null ? null : source.read();
    } catch (final Exception exception) {
      return null;
    }
  }

  public static FailClosedPolicyDecisionPoint fromRules(
      final String version, final List<CompiledPolicyRule> rules) {
    return new FailClosedPolicyDecisionPoint(CompiledPolicyArtifact.fromRules(version, rules));
  }
}
