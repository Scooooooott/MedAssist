package com.medassist.retrieval.application.model;

import java.util.List;

public record SearchOutcome(SearchQuery query, List<RetrievedChunk> chunks, long embeddingMs, long retrievalMs) {
  public SearchOutcome {
    chunks = List.copyOf(chunks);
  }
}
