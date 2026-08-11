package com.medassist.ingestion.context;

import java.util.Objects;

/** Provider response containing only the context prefix to store beside the chunk. */
public record ContextLlmResponse(String contextPrefix) {
  public ContextLlmResponse {
    Objects.requireNonNull(contextPrefix, "contextPrefix");
    if (contextPrefix.isBlank()) {
      throw new IllegalArgumentException("contextPrefix must not be blank");
    }
  }
}
