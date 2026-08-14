package com.medassist.common.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SafeTelemetryAttributesTest {
  @Test
  void dropsTextAndUnknownAttributes() {
    final Map<String, String> safe =
        SafeTelemetryAttributes.retainAllowed(
            Map.of(
                "role", "CLINICIAN",
                "query", "patient name and diagnosis",
                "chunk.text", "clinical evidence",
                "retrieval.result_count", 8));

    assertEquals(Map.of("role", "CLINICIAN", "retrieval.result_count", "8"), safe);
    assertFalse(safe.containsKey("query"));
    assertFalse(safe.containsKey("chunk.text"));
  }
}
