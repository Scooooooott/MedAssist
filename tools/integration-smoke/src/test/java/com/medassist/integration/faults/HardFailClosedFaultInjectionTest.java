package com.medassist.integration.faults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("fault-pr")
@Tag("fault-nightly")
class HardFailClosedFaultInjectionTest {
  private final FaultInjectionHarness harness = new FaultInjectionHarness();

  @Test
  void deidentificationUnavailableRejectsAndRecoversWithoutPlaintextFallback() {
    final FaultInjectionHarness.SafetyResult failed =
        harness.deidentify(FaultInjectionHarness.unavailable());

    assertFalse(failed.accepted());
    assertEquals("DEIDENTIFICATION_UNAVAILABLE", failed.code());
    assertNull(failed.transformedText());

    final FaultInjectionHarness.SafetyResult recovered =
        harness.deidentify(() -> "synthetic-deidentified-input");
    assertTrue(recovered.accepted());
    assertEquals("synthetic-deidentified-input", recovered.transformedText());
  }

  @Test
  void deidentificationTimeoutRejectsAndRecoversWithoutPlaintextFallback() {
    final FaultInjectionHarness.SafetyResult failed =
        harness.deidentify(FaultInjectionHarness.timeout());

    assertFalse(failed.accepted());
    assertEquals("DEIDENTIFICATION_TIMEOUT", failed.code());
    assertNull(failed.transformedText());

    final FaultInjectionHarness.SafetyResult recovered =
        harness.deidentify(() -> "synthetic-deidentified-input");
    assertTrue(recovered.accepted());
  }

  @Test
  void unavailablePolicyDecisionPointDeniesEveryPepAndRecovers() {
    final FaultInjectionHarness.PolicyResult failed =
        harness.authorize(FaultInjectionHarness.unavailablePolicy());

    assertTrue(failed.allowed().isEmpty());
    assertEquals(
        List.of("gateway", "retrieval", "clinical-data", "ingestion", "agent"), failed.denied());

    final FaultInjectionHarness.PolicyResult recovered = harness.authorize(() -> true);
    assertTrue(recovered.denied().isEmpty());
    assertEquals(5, recovered.allowed().size());
  }

  @Test
  void allLlmProvidersUnavailableFailsExplicitlyWithoutGeneratedContentAndRecovers() {
    final FaultInjectionHarness.LlmResult failed =
        harness.generate(
            List.of(FaultInjectionHarness.unavailable(), FaultInjectionHarness.unavailable()));

    assertFalse(failed.succeeded());
    assertEquals("LLM_ALL_PROVIDERS_UNAVAILABLE", failed.code());
    assertNull(failed.generatedContent());
    assertEquals(2, failed.attempts());

    final FaultInjectionHarness.LlmResult recovered =
        harness.generate(List.of(() -> "safe-cited-answer"));
    assertTrue(recovered.succeeded());
    assertEquals("safe-cited-answer", recovered.generatedContent());
  }
}
