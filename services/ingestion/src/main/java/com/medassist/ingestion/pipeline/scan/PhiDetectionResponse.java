package com.medassist.ingestion.pipeline.scan;

import java.util.Objects;
import java.util.Set;

/** A source-text-free summary of a Detect response. */
public record PhiDetectionResponse(Set<String> entityTypes) {
  public PhiDetectionResponse {
    entityTypes = Set.copyOf(Objects.requireNonNull(entityTypes, "entityTypes"));
    if (entityTypes.stream().anyMatch(type -> type == null || type.isBlank())) {
      throw new IllegalArgumentException("entity types must not contain blank values");
    }
  }

  public boolean hasPhi() {
    return !entityTypes.isEmpty();
  }
}
