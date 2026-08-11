package com.medassist.ingestion.pipeline.scan;

import java.time.Duration;
import java.util.Objects;

/** A single text fragment and its transport deadline. */
public record PhiDetectionRequest(String text, Duration timeout) {
  public PhiDetectionRequest {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }
}
