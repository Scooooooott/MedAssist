package com.medassist.ingestion.batch.steps.index;

import com.medassist.ingestion.chunking.ChunkingOptions;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import java.util.Objects;

/** Explicit runtime choices for the chunk, context, and embedding boundary. */
public record IndexPreparationConfiguration(
    String chunkingStrategyId,
    ChunkingOptions chunkingOptions,
    ContextualRetrievalMode contextualRetrievalMode,
    String contextPromptVersion,
    EmbeddingModel embeddingModel) {
  public IndexPreparationConfiguration {
    chunkingStrategyId = requireText(chunkingStrategyId, "chunkingStrategyId");
    Objects.requireNonNull(chunkingOptions, "chunkingOptions");
    Objects.requireNonNull(contextualRetrievalMode, "contextualRetrievalMode");
    contextPromptVersion = requireText(contextPromptVersion, "contextPromptVersion");
    Objects.requireNonNull(embeddingModel, "embeddingModel");
  }

  private static String requireText(final String value, final String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
