package com.medassist.retrieval.application.model;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record RetrievedChunk(
    UUID chunkId,
    UUID documentVersionId,
    int ordinal,
    String sectionPath,
    String text,
    int tokenCount,
    long sourceCharStart,
    long sourceCharEnd,
    double score,
    String retrievalMethod,
    String distanceMetric,
    String docType,
    String publisher,
    String sourceTitle,
    String version,
    LocalDate effectiveDate,
    Map<String, String> metadata) {
  public RetrievedChunk {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
