package com.medassist.identitypolicy;

public interface PolicyDecisionPoint {
  PolicyDecisionResponse decide(PolicyDecisionRequest request);
}
