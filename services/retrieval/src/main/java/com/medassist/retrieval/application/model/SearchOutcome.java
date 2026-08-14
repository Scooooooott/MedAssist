package com.medassist.retrieval.application.model;

import com.medassist.common.resilience.Degradation;
import java.util.List;

public record SearchOutcome(
    SearchQuery query,
    List<RetrievedChunk> chunks,
    long embeddingMs,
    long retrievalMs,
    boolean degraded,
    List<String> degradationReasons,
    List<Degradation> degradations) {
  public SearchOutcome {
    chunks = List.copyOf(chunks);
    degradations = degradations == null ? List.of() : List.copyOf(degradations);
    degradationReasons =
        degradationReasons == null || degradationReasons.isEmpty()
            ? degradations.stream().map(Degradation::code).toList()
            : List.copyOf(degradationReasons);
    degraded = degraded || !degradations.isEmpty();
  }

  public SearchOutcome(
      final SearchQuery query,
      final List<RetrievedChunk> chunks,
      final long embeddingMs,
      final long retrievalMs,
      final boolean degraded,
      final List<String> degradationReasons) {
    this(query, chunks, embeddingMs, retrievalMs, degraded, degradationReasons, List.of());
  }

  public SearchOutcome(
      final SearchQuery query,
      final List<RetrievedChunk> chunks,
      final long embeddingMs,
      final long retrievalMs) {
    this(query, chunks, embeddingMs, retrievalMs, false, List.of(), List.of());
  }
}
