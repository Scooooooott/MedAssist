package com.medassist.ingestion.pipeline.index;

import java.util.List;
import java.util.Objects;

/** Complete immutable result for downstream Batch/JDBC adapters. */
public record IndexingResult(
    IndexableDocument document, List<IndexableChunk> chunks, List<IndexableEmbedding> embeddings) {
  public IndexingResult {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(chunks, "chunks");
    Objects.requireNonNull(embeddings, "embeddings");
    chunks = List.copyOf(chunks);
    embeddings = List.copyOf(embeddings);
  }
}
