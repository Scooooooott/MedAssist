package com.medassist.identitypolicy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyDecisionPointTest {
  private static final CompiledPolicyRule READ_RULE =
      new CompiledPolicyRule(
          Role.CLINICIAN.name(),
          Action.READ.name(),
          ResourceType.CLINICAL_DATA.name(),
          Map.of("contentDomain", "CLINICAL"),
          List.of(Obligation.of("ACTIVE_VERSION_ONLY")));

  @Test
  void allowsCompiledRuleAndReturnsObligations() {
    final PolicyDecisionPoint point =
        new InMemoryPolicyDecisionPoint("m4-test", List.of(READ_RULE));

    final PolicyDecisionResponse response =
        point.decide(
            new PolicyDecisionRequest(
                "actor-1",
                Role.CLINICIAN,
                Action.READ,
                ResourceType.CLINICAL_DATA,
                Map.of("contentDomain", "CLINICAL")));

    assertThat(response.allowed()).isTrue();
    assertThat(response.obligations()).containsExactly(Obligation.of("ACTIVE_VERSION_ONLY"));
  }

  @Test
  void deniesUnknownDimensionsAndNonMatchingAttributes() {
    final PolicyDecisionPoint point =
        new InMemoryPolicyDecisionPoint("m4-test", List.of(READ_RULE));

    final PolicyDecisionResponse unknown =
        point.decide(
            PolicyDecisionRequest.of(
                "actor-1",
                "UNKNOWN_ROLE",
                Action.READ.name(),
                ResourceType.CLINICAL_DATA.name(),
                Map.of()));
    final PolicyDecisionResponse wrongAttribute =
        point.decide(
            new PolicyDecisionRequest(
                "actor-1",
                Role.CLINICIAN,
                Action.READ,
                ResourceType.CLINICAL_DATA,
                Map.of("contentDomain", "PUBLIC")));

    assertThat(unknown.allowed()).isFalse();
    assertThat(wrongAttribute.allowed()).isFalse();
  }

  @Test
  void sourceFailureFailsClosed() {
    final PolicyDecisionPoint point =
        new FailClosedPolicyDecisionPoint(
            () -> {
              throw new IllegalStateException("compiler output unavailable");
            });

    final PolicyDecisionResponse response =
        point.decide(
            new PolicyDecisionRequest(
                "actor-1", Role.ADMIN, Action.READ, ResourceType.AUDIT_LOG, Map.of()));

    assertThat(response.allowed()).isFalse();
    assertThat(response.obligations()).isEmpty();
  }
}
