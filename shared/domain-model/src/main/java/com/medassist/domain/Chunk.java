package com.medassist.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record Chunk(
    UUID id,
    UUID documentVersionId,
    int ordinal,
    String sectionPath,
    String text,
    int tokenCount,
    SourceRange sourceRange,
    Map<String, String> metadata) {
  public Chunk {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    Objects.requireNonNull(sectionPath, "sectionPath");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(sourceRange, "sourceRange");
    metadata = Map.copyOf(metadata);
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must be non-negative");
    }
    if (tokenCount < 0) {
      throw new IllegalArgumentException("tokenCount must be non-negative");
    }
  }
}
