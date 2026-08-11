package com.medassist.ingestion.context;

import java.util.Objects;

/** Derived context plus the durable outcome of the generation attempt. */
public record ContextCacheEntry(
    String contextPrefix, ContextCacheGenerationStatus generationStatus) {
  public ContextCacheEntry {
    Objects.requireNonNull(contextPrefix, "contextPrefix");
    Objects.requireNonNull(generationStatus, "generationStatus");
    if (contextPrefix.isBlank()) {
      throw new IllegalArgumentException("contextPrefix must not be blank");
    }
  }
}
