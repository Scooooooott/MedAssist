package com.medassist.agent.execution;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Transient evidence retained only for the current generation and verification call. */
public record RuntimeSafetyEvidence(List<RuntimeEvidenceChunk> chunks) {
  public RuntimeSafetyEvidence {
    Objects.requireNonNull(chunks, "chunks");
    chunks = List.copyOf(chunks);
    final Set<UUID> identifiers = new HashSet<>();
    for (final RuntimeEvidenceChunk chunk : chunks) {
      Objects.requireNonNull(chunk, "chunks cannot contain null");
      if (!identifiers.add(chunk.chunkId())) {
        throw new IllegalArgumentException("runtime evidence contains duplicate chunk ids");
      }
    }
  }

  public static RuntimeSafetyEvidence empty() {
    return new RuntimeSafetyEvidence(List.of());
  }

  @Override
  public String toString() {
    return "RuntimeSafetyEvidence[chunkCount=" + chunks.size() + "]";
  }
}
