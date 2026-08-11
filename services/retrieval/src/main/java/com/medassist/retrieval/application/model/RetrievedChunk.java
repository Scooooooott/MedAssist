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
    String documentStatus,
    boolean stale,
    Integer vectorRank,
    Integer lexicalRank,
    Double vectorScore,
    Double lexicalScore,
    Double fusedScore,
    Map<String, String> metadata) {
  public RetrievedChunk {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public RetrievedChunk(
      final UUID chunkId,
      final UUID documentVersionId,
      final int ordinal,
      final String sectionPath,
      final String text,
      final int tokenCount,
      final long sourceCharStart,
      final long sourceCharEnd,
      final double score,
      final String retrievalMethod,
      final String distanceMetric,
      final String docType,
      final String publisher,
      final String sourceTitle,
      final String version,
      final LocalDate effectiveDate,
      final Map<String, String> metadata) {
    this(
        chunkId,
        documentVersionId,
        ordinal,
        sectionPath,
        text,
        tokenCount,
        sourceCharStart,
        sourceCharEnd,
        score,
        retrievalMethod,
        distanceMetric,
        docType,
        publisher,
        sourceTitle,
        version,
        effectiveDate,
        "ACTIVE",
        false,
        null,
        null,
        null,
        null,
        null,
        metadata);
  }
}
