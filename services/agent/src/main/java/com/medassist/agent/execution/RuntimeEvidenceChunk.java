package com.medassist.agent.execution;

import java.util.Objects;
import java.util.UUID;

/** Original chunk text kept in memory for citation verification only. */
public record RuntimeEvidenceChunk(UUID chunkId, String content) {
  public RuntimeEvidenceChunk {
    Objects.requireNonNull(chunkId, "chunkId");
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("evidence content must not be blank");
    }
  }

  @Override
  public String toString() {
    return "RuntimeEvidenceChunk[chunkId=" + chunkId + ", content=<redacted>]";
  }
}
