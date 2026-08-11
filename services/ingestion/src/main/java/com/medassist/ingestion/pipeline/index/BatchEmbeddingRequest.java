package com.medassist.ingestion.pipeline.index;

import java.util.List;
import java.util.Objects;

/** Request carrying the fixed model contract and the text used only for embedding. */
public record BatchEmbeddingRequest(EmbeddingModel model, List<EmbeddingInput> inputs) {
  public BatchEmbeddingRequest {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(inputs, "inputs");
    inputs = List.copyOf(inputs);
    for (final EmbeddingInput input : inputs) {
      Objects.requireNonNull(input, "inputs must not contain null");
    }
  }
}
