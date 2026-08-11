package com.medassist.identitypolicy;

import java.util.List;
import java.util.Objects;

public record PolicyDecisionResponse(boolean allowed, String reason, List<Obligation> obligations) {
  public PolicyDecisionResponse {
    if (Objects.requireNonNull(reason, "reason").isBlank()) {
      throw new IllegalArgumentException("decision reason is required");
    }
    obligations = List.copyOf(Objects.requireNonNull(obligations, "obligations"));
  }

  public static PolicyDecisionResponse deny(final String reason) {
    return new PolicyDecisionResponse(false, reason, List.of());
  }

  public static PolicyDecisionResponse allow(
      final String reason, final List<Obligation> obligations) {
    return new PolicyDecisionResponse(true, reason, obligations);
  }
}
