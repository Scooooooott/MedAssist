package com.medassist.ingestion.pipeline.index;

import java.util.Objects;
import java.util.UUID;

/** One chunk identity and the context-enriched text sent to model-svc. */
public record EmbeddingInput(UUID chunkId, String text) {
  public EmbeddingInput {
    Objects.requireNonNull(chunkId, "chunkId");
    Objects.requireNonNull(text, "text");
    if (text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
  }
}
