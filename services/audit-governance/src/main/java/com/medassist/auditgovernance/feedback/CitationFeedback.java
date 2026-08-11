package com.medassist.auditgovernance.feedback;

import java.util.Objects;

public record CitationFeedback(String citationId, CitationRating rating) {
  public CitationFeedback {
    if (citationId == null || citationId.isBlank()) {
      throw new IllegalArgumentException("citationId is required");
    }
    Objects.requireNonNull(rating, "rating");
  }
}
