package com.medassist.agent.execution;

import java.util.Objects;
import java.util.UUID;

/** Chunk metadata that is safe to expose to the agent; it intentionally has no text field. */
public record SafeChunkProjection(
    UUID chunkId, String version, String source, String citationLocator) {
  public SafeChunkProjection {
    Objects.requireNonNull(chunkId, "chunkId");
    requireText(version, "version");
    requireText(source, "source");
    requireText(citationLocator, "citationLocator");
  }

  private static void requireText(final String value, final String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
