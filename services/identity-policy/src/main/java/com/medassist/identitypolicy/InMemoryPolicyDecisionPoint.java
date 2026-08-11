package com.medassist.identitypolicy;

import java.util.List;

/** Named convenience implementation for callers that already hold compiler output in memory. */
public final class InMemoryPolicyDecisionPoint extends FailClosedPolicyDecisionPoint {
  public InMemoryPolicyDecisionPoint(final CompiledPolicyArtifact artifact) {
    super(artifact);
  }

  public InMemoryPolicyDecisionPoint(final String version, final List<CompiledPolicyRule> rules) {
    super(CompiledPolicyArtifact.fromRules(version, rules));
  }
}
