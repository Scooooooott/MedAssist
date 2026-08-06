package com.medassist.domain;

import java.util.Objects;

public record RetrievalResult(Chunk chunk, double score, RetrievalMethod retrievalMethod) {
  public RetrievalResult {
    Objects.requireNonNull(chunk, "chunk");
    Objects.requireNonNull(retrievalMethod, "retrievalMethod");
  }
}
