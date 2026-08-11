package com.medassist.ingestion.pipeline.index;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Validated embedding record ready for a later JDBC adapter. */
public record IndexableEmbedding(
    UUID chunkId, String modelName, String modelVersion, int dimension, List<Float> values) {
  public IndexableEmbedding {
    Objects.requireNonNull(chunkId, "chunkId");
    modelName = requireText(modelName, "modelName");
    modelVersion = requireText(modelVersion, "modelVersion");
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be positive");
    }
    Objects.requireNonNull(values, "values");
    values = List.copyOf(values);
    if (values.size() != dimension) {
      throw new IllegalArgumentException("embedding dimension does not match values");
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
