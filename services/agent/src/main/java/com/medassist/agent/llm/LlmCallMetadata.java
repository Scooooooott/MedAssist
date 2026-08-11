package com.medassist.agent.llm;

import java.time.Duration;
import java.util.Objects;

/** Provider-neutral metadata for one model call. */
public record LlmCallMetadata(String provider, String model, Duration timeout) {
  public LlmCallMetadata {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("provider is required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model is required");
    }
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  public static LlmCallMetadata unconfigured() {
    return new LlmCallMetadata("unconfigured", "unconfigured", Duration.ofSeconds(30));
  }
}
