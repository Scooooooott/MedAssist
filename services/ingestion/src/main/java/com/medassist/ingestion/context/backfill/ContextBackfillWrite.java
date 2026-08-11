package com.medassist.ingestion.context.backfill;

import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import java.util.List;
import java.util.Objects;

/** Atomic context backfill write for one document version. */
public record ContextBackfillWrite(
    ContextualRetrievalMode mode,
    String promptVersion,
    EmbeddingModel model,
    List<ContextBackfillChunkWrite> chunks) {
  public ContextBackfillWrite {
    Objects.requireNonNull(mode, "mode");
    promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
    Objects.requireNonNull(model, "model");
    chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    if (mode == ContextualRetrievalMode.OFF
        || promptVersion.isBlank()
        || chunks.isEmpty()
        || chunks.stream().anyMatch(chunk -> chunk.embedding().size() != model.dimension())) {
      throw new IllegalArgumentException("context backfill write is invalid");
    }
  }
}
