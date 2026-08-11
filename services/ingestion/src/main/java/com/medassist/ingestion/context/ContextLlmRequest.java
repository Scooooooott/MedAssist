package com.medassist.ingestion.context;

import java.util.Objects;
import java.util.UUID;

/** Provider request with shared and per-chunk prompt parts kept separate. */
public record ContextLlmRequest(
    UUID documentVersionId,
    int chunkOrdinal,
    String promptVersion,
    String sharedDocumentPromptPrefix,
    String originalChunkText) {
  public ContextLlmRequest {
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    Objects.requireNonNull(promptVersion, "promptVersion");
    Objects.requireNonNull(sharedDocumentPromptPrefix, "sharedDocumentPromptPrefix");
    Objects.requireNonNull(originalChunkText, "originalChunkText");
    if (chunkOrdinal < 0) {
      throw new IllegalArgumentException("chunkOrdinal must be non-negative");
    }
  }
}
