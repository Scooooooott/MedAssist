package com.medassist.domain;

import java.util.Objects;

/**
 * PHI detection metadata. The detected source value is intentionally excluded so logs, audit
 * records, and DTOs cannot persist raw PHI by construction.
 */
public record PhiEntity(String entityType, int start, int end, double score, String recognizer) {
  public PhiEntity {
    Objects.requireNonNull(entityType, "entityType");
    Objects.requireNonNull(recognizer, "recognizer");
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("invalid entity span");
    }
    if (score < 0.0D || score > 1.0D) {
      throw new IllegalArgumentException("score must be within [0, 1]");
    }
  }
}
