package com.medassist.auditgovernance.feedback;

public record SupportingSpan(String documentId, int start, int end) {
  public SupportingSpan {
    if (documentId == null || documentId.isBlank()) {
      throw new IllegalArgumentException("documentId is required");
    }
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("supporting span must have positive bounds");
    }
  }
}
