package com.medassist.agent.security;

@FunctionalInterface
public interface EgressGuard {
  EgressDecision inspect(EgressRequest request);

  default EgressDecision check(final EgressRequest request) {
    return inspect(request);
  }
}
