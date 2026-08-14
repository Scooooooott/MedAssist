package com.medassist.common.tracing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Central low-cardinality allowlist for trace and metric attributes. */
public final class SafeTelemetryAttributes {
  private static final int MAX_VALUE_LENGTH = 128;
  private static final Set<String> ALLOWED =
      Set.of(
          "role",
          "retrieval.mode",
          "retrieval.result_count",
          "model.name",
          "model.version",
          "llm.provider",
          "llm.destination",
          "token.input_count",
          "token.output_count",
          "request.abstained",
          "request.reason_code",
          "degradation.code",
          "degradation.stage",
          "degradation.fallback_mode",
          "generation.operation",
          "generation.status",
          "generation.event_type",
          "generation.resumed",
          "rpc.service",
          "rpc.method",
          "queue.wait_ms",
          "inference.duration_ms");

  private SafeTelemetryAttributes() {}

  public static Map<String, String> retainAllowed(final Map<String, ?> attributes) {
    final Map<String, String> safe = new LinkedHashMap<>();
    if (attributes == null) {
      return Map.of();
    }
    attributes.forEach(
        (key, value) -> {
          if (ALLOWED.contains(key) && value != null) {
            final String text = String.valueOf(value);
            safe.put(key, text.substring(0, Math.min(text.length(), MAX_VALUE_LENGTH)));
          }
        });
    return Map.copyOf(safe);
  }
}
