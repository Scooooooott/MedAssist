package com.medassist.ingestion.pipeline.index;

import java.util.Objects;

/** Immutable identity and dimension contract for one embedding model deployment. */
public record EmbeddingModel(String name, String version, int dimension) {
  public EmbeddingModel {
    name = requireText(name, "name");
    version = requireText(version, "version");
    if (dimension <= 0) {
      throw new IllegalArgumentException("dimension must be positive");
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
