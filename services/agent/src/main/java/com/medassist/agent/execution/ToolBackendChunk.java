package com.medassist.agent.execution;

import java.util.Objects;
import java.util.UUID;

/** Backend boundary object. The content is deliberately discarded by ToolResultProjector. */
public record ToolBackendChunk(
    UUID chunkId,
    long rangeStart,
    long rangeEnd,
    String chunkHash,
    double score,
    int rank,
    String version,
    String source,
    String citationLocator,
    String content) {
  public ToolBackendChunk {
    Objects.requireNonNull(chunkId, "chunkId");
    Objects.requireNonNull(chunkHash, "chunkHash");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(citationLocator, "citationLocator");
    Objects.requireNonNull(content, "content");
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
