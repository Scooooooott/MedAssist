package com.medassist.retrieval.api.dto;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record RetrievalResultDto(
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
    String documentStatus,
    boolean stale,
    Integer vectorRank,
    Integer lexicalRank,
    Double vectorScore,
    Double lexicalScore,
    Double fusedScore,
    Map<String, String> metadata) {
  public RetrievalResultDto {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
