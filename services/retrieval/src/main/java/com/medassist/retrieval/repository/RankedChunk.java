package com.medassist.retrieval.repository;

import com.medassist.retrieval.application.model.RetrievedChunk;

public record RankedChunk(RetrievedChunk chunk, int rank, double channelScore) {
  public RankedChunk {
    if (chunk == null) {
      throw new IllegalArgumentException("chunk is required");
    }
    if (rank < 1) {
      throw new IllegalArgumentException("rank must be positive");
    }
  }
}
