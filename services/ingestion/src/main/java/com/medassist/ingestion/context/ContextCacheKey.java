package com.medassist.ingestion.context;

import java.util.Objects;
import java.util.UUID;

/** Stable cache identity required for contextual retrieval. */
public record ContextCacheKey(
    UUID documentVersionId,
    String chunkingStrategyId,
    int chunkOrdinal,
    ContextualRetrievalMode mode,
    String promptVersion) {
  public ContextCacheKey {
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    Objects.requireNonNull(chunkingStrategyId, "chunkingStrategyId");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(promptVersion, "promptVersion");
    if (chunkingStrategyId.isBlank()) {
      throw new IllegalArgumentException("chunkingStrategyId must not be blank");
    }
    if (chunkOrdinal < 0) {
      throw new IllegalArgumentException("chunkOrdinal must be non-negative");
    }
    if (promptVersion.isBlank()) {
      throw new IllegalArgumentException("promptVersion must not be blank");
    }
  }
}
