package com.medassist.retrieval.application;

import java.util.Objects;

public record RetryStatus(int attempt, int maxAttempts, String reason) {
  public RetryStatus {
    if (attempt < 1 || maxAttempts < attempt) {
      throw new IllegalArgumentException("invalid retry attempt");
    }
    if (Objects.requireNonNull(reason, "reason").isBlank()) {
      throw new IllegalArgumentException("retry reason is required");
    }
  }
}
