package com.medassist.ingestion.context;

import com.medassist.domain.Chunk;
import java.util.Objects;

/** Chunk view that keeps original text immutable and exposes consumer-specific text policies. */
public record ContextualChunk(Chunk chunk, String contextPrefix, ContextStatus status) {
  public ContextualChunk {
    Objects.requireNonNull(chunk, "chunk");
    Objects.requireNonNull(contextPrefix, "contextPrefix");
    Objects.requireNonNull(status, "status");
  }

  public String originalText() {
    return chunk.text();
  }

  public String embeddingText() {
    return EmbeddingTextPolicy.embeddingText(contextPrefix, originalText());
  }

  public String lexicalText() {
    return EmbeddingTextPolicy.lexicalText(originalText());
  }

  public String rerankText() {
    return EmbeddingTextPolicy.rerankText(originalText());
  }

  public String finalContextText() {
    return EmbeddingTextPolicy.finalContextText(originalText());
  }

  public String generationText() {
    return EmbeddingTextPolicy.generationText(originalText());
  }

  public String citationText() {
    return EmbeddingTextPolicy.citationText(originalText());
  }

  public String displayText() {
    return EmbeddingTextPolicy.displayText(originalText());
  }
}
