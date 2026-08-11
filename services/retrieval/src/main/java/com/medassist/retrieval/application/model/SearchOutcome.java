package com.medassist.retrieval.application.model;

import java.util.List;

public record SearchOutcome(
    SearchQuery query,
    List<RetrievedChunk> chunks,
    long embeddingMs,
    long retrievalMs,
    boolean degraded,
    List<String> degradationReasons) {
  public SearchOutcome {
    chunks = List.copyOf(chunks);
    degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
  }

  public SearchOutcome(
      final SearchQuery query,
      final List<RetrievedChunk> chunks,
      final long embeddingMs,
      final long retrievalMs) {
    this(query, chunks, embeddingMs, retrievalMs, false, List.of());
  }
}
