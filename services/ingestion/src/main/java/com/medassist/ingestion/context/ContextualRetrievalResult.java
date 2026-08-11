package com.medassist.ingestion.context;

import java.util.List;
import java.util.Objects;

/** Result of context preparation without mutating the source chunks. */
public record ContextualRetrievalResult(List<ContextualChunk> chunks) {
  public ContextualRetrievalResult {
    Objects.requireNonNull(chunks, "chunks");
    chunks = List.copyOf(chunks);
  }
}
