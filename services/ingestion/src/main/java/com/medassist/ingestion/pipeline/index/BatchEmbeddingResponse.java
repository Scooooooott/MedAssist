package com.medassist.ingestion.pipeline.index;

import java.util.List;
import java.util.Objects;

/** Raw batch response; the processor validates it before creating indexable records. */
public record BatchEmbeddingResponse(
    String modelName, String modelVersion, int dimension, List<EmbeddingVector> vectors) {
  public BatchEmbeddingResponse {
    modelName = Objects.requireNonNull(modelName, "modelName");
    modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
    Objects.requireNonNull(vectors, "vectors");
    vectors = List.copyOf(vectors);
    for (final EmbeddingVector vector : vectors) {
      Objects.requireNonNull(vector, "vectors must not contain null");
    }
  }
}
