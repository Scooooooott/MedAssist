package com.medassist.agent.generation;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GenerationOutputApproverTest {
  private final GenerationOutputApprover approver = new GenerationOutputApprover(128);

  @ParameterizedTest
  @ValueSource(strings = {"patient@example.org", "MRN: ABC-123", "2026-08-11"})
  void rejectsSensitiveFinalAnswers(final String answer) {
    assertThrows(
        GenerationOutputApprover.OutputApprovalException.class,
        () -> approver.approveAndChunk(answer, "unrelated query"));
  }
}
