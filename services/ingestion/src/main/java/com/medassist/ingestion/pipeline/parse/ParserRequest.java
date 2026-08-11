package com.medassist.ingestion.pipeline.parse;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Timeout-aware request sent to the parser sidecar. */
public record ParserRequest(
    URI storageUri,
    String mimeType,
    String sourceId,
    Map<String, String> options,
    Duration timeout) {

  public ParserRequest {
    Objects.requireNonNull(storageUri, "storageUri must not be null");
    requireText(mimeType, "mimeType");
    requireText(sourceId, "sourceId");
    options = Map.copyOf(Objects.requireNonNull(options, "options"));
    requireTimeout(timeout);
  }

  private static void requireText(final String value, final String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requireTimeout(final Duration timeout) {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }
}
