package com.medassist.agent.state;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The only chunk data that may cross the agent persistence boundary. */
public record ChunkCandidateMetadata(
    UUID chunkId, long rangeStart, long rangeEnd, String chunkHash, double score, int rank)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public ChunkCandidateMetadata {
    Objects.requireNonNull(chunkId, "chunkId");
    Objects.requireNonNull(chunkHash, "chunkHash");
    if (rangeStart < 0 || rangeEnd < rangeStart) {
      throw new IllegalArgumentException("invalid chunk range");
    }
    if (!Double.isFinite(score)) {
      throw new IllegalArgumentException("score must be finite");
    }
    if (rank < 1) {
      throw new IllegalArgumentException("rank must be positive");
    }
  }
}
