package com.medassist.ingestion.pipeline.index;

import com.medassist.ingestion.chunking.ChunkingOptions;
import com.medassist.ingestion.context.ContextDocument;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.model.ParseAndDeidentifyState;
import java.util.Objects;

/** All explicit choices required to convert one safe parse result into indexable records. */
public record IndexingRequest(
    ParseAndDeidentifyState state,
    String documentTitle,
    String chunkingStrategyId,
    ChunkingOptions chunkingOptions,
    ContextDocument contextDocument,
    ContextualRetrievalMode contextualRetrievalMode,
    String contextPromptVersion,
    EmbeddingModel embeddingModel) {
  public IndexingRequest {
    Objects.requireNonNull(state, "state");
    documentTitle = requireText(documentTitle, "documentTitle");
    chunkingStrategyId = requireText(chunkingStrategyId, "chunkingStrategyId");
    Objects.requireNonNull(chunkingOptions, "chunkingOptions");
    Objects.requireNonNull(contextDocument, "contextDocument");
    Objects.requireNonNull(contextualRetrievalMode, "contextualRetrievalMode");
    contextPromptVersion = requireText(contextPromptVersion, "contextPromptVersion");
    Objects.requireNonNull(embeddingModel, "embeddingModel");
    if (!state.documentVersionId().equals(contextDocument.documentVersionId())) {
      throw new IllegalArgumentException("context document version must match state");
    }
  }

  private static String requireText(final String value, final String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
