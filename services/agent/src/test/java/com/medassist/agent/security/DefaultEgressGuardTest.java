package com.medassist.agent.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultEgressGuardTest {
  private final DefaultEgressGuard guard = new DefaultEgressGuard();

  @Test
  void allowsKnownDestinationAndSafeContentClass() {
    final EgressDecision decision =
        guard.inspect(
            new EgressRequest(
                "external_llm",
                ContentClass.DEIDENTIFIED_QUERY,
                EgressSource.USER_QUERY,
                "What are the treatment options?",
                false));

    assertTrue(decision.allowed());
    assertEquals(EgressReason.ALLOWED, decision.reason());
    assertEquals("ALLOW", decision.auditEvent().action());
  }

  @Test
  void deniesRawUserQuestionEvenWhenItLooksDeidentified() {
    final EgressDecision decision =
        guard.inspect(
            new EgressRequest(
                "EXTERNAL_LLM",
                ContentClass.DEIDENTIFIED_QUERY,
                EgressSource.USER_QUERY,
                "What are the treatment options?",
                true));

    assertFalse(decision.allowed());
    assertEquals(EgressReason.RAW_USER_QUESTION, decision.reason());
  }

  @Test
  void deniesPhiIdentifiersDatesAddressesAndNames() {
    final String[] sensitivePayloads = {
      "patient_id=MRN-12345",
      "Patient John Smith",
      "visit date 2026-08-10",
      "full address: 12 Example Street, Paris",
      "contact john.smith@example.org"
    };

    for (final String payload : sensitivePayloads) {
      final EgressDecision decision =
          guard.inspect(new EgressRequest("LOCAL_MODEL", ContentClass.AGGREGATE_ONLY, payload));
      assertFalse(decision.allowed(), payload);
      assertEquals(EgressReason.SENSITIVE_CONTENT, decision.reason(), payload);
    }
  }

  @Test
  void unknownDestinationAndContentClassFailClosed() {
    final EgressDecision unknownDestination =
        guard.inspect(
            new EgressRequest("new-model-provider", ContentClass.AGGREGATE_ONLY, "count=3"));
    final EgressDecision unknownClass =
        guard.inspect(new EgressRequest("LOCAL_MODEL", ContentClass.UNKNOWN, "count=3"));

    assertFalse(unknownDestination.allowed());
    assertEquals(EgressReason.UNKNOWN_DESTINATION, unknownDestination.reason());
    assertFalse(unknownClass.allowed());
    assertEquals(EgressReason.UNKNOWN_CONTENT_CLASS, unknownClass.reason());
  }

  @Test
  void auditContainsHashAndMetadataButNeverPayload() {
    final String sensitivePayload = "Patient John Smith, MRN-12345";
    final EgressDecision decision =
        guard.inspect(
            new EgressRequest("EXTERNAL_LLM", ContentClass.DEIDENTIFIED_QUERY, sensitivePayload));

    assertFalse(decision.auditEvent().containsPayload());
    assertNotEquals(sensitivePayload, decision.auditEvent().payloadHash());
    assertFalse(decision.auditEvent().toString().contains(sensitivePayload));
    assertTrue(decision.auditEvent().sensitiveFindings().contains(SensitiveFinding.NAME));
    assertEquals(
        Set.of(SensitiveFinding.NAME, SensitiveFinding.PATIENT_ID),
        decision.auditEvent().sensitiveFindings());
  }
}
