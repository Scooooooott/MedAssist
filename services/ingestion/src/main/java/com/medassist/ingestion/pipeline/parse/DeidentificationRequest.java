package com.medassist.ingestion.pipeline.parse;

import java.time.Duration;
import java.util.Objects;

/** Timeout-aware request for one text fragment within a stable document boundary. */
public record DeidentificationRequest(
    String text, String policy, String documentKey, Duration timeout) {
  public DeidentificationRequest {
    Objects.requireNonNull(text, "text must not be null");
    requireText(policy, "policy");
    requireText(documentKey, "documentKey");
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  private static void requireText(final String value, final String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
