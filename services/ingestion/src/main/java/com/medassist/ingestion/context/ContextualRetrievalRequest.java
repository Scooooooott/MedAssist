package com.medassist.ingestion.context;

import com.medassist.domain.Chunk;
import java.util.List;
import java.util.Objects;

/** Immutable request for preparing context for one document version. */
public record ContextualRetrievalRequest(
    ContextDocument document,
    ContextualRetrievalMode mode,
    String promptVersion,
    List<Chunk> chunks) {
  public ContextualRetrievalRequest {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(mode, "mode");
    promptVersion = requirePromptVersion(promptVersion);
    Objects.requireNonNull(chunks, "chunks");
    chunks = List.copyOf(chunks);
    for (final Chunk chunk : chunks) {
      Objects.requireNonNull(chunk, "chunks must not contain null");
      if (!document.documentVersionId().equals(chunk.documentVersionId())) {
        throw new IllegalArgumentException("all chunks must belong to the document version");
      }
    }
  }

  private static String requirePromptVersion(final String value) {
    Objects.requireNonNull(value, "promptVersion");
    if (value.isBlank()) {
      throw new IllegalArgumentException("promptVersion must not be blank");
    }
    return value;
  }
}
