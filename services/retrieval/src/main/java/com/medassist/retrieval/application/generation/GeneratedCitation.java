package com.medassist.retrieval.application.generation;

import java.util.Objects;
import java.util.UUID;

public record GeneratedCitation(
    UUID chunkId, UUID documentVersionId, String quotedSpan, String relevance) {
  public GeneratedCitation {
    Objects.requireNonNull(chunkId, "chunkId");
    Objects.requireNonNull(documentVersionId, "documentVersionId");
    Objects.requireNonNull(quotedSpan, "quotedSpan");
    Objects.requireNonNull(relevance, "relevance");
  }
}
