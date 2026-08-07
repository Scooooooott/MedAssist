package com.medassist.domain;

import java.util.Objects;
import java.util.UUID;

public record Citation(
    UUID chunkId, UUID documentVersionId, int startOffset, int endOffset, String quotedSpanHash) {
  public Citation {
    Objects.requireNonNull(chunkId, "chunkId");
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    Objects.requireNonNull(quotedSpanHash, "quotedSpanHash");
    if (startOffset < 0 || endOffset < startOffset) {
      throw new IllegalArgumentException("invalid citation span");
    }
  }
}
