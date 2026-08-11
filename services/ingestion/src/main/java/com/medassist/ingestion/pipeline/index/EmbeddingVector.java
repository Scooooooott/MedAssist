package com.medassist.ingestion.pipeline.index;

import java.util.List;
import java.util.Objects;

/** Immutable vector values returned by the embedding port. */
public record EmbeddingVector(List<Float> values) {
  public EmbeddingVector {
    Objects.requireNonNull(values, "values");
    values = List.copyOf(values);
    for (final Float value : values) {
      Objects.requireNonNull(value, "values must not contain null");
      if (!Float.isFinite(value)) {
        throw new IllegalArgumentException("embedding values must be finite");
      }
    }
  }
}
