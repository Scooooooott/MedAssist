package com.medassist.retrieval.api.dto;

import java.time.Instant;
import java.util.List;

public record AnswerResponse(
    String query,
    String answer,
    List<CitationDto> citations,
    boolean sufficientEvidence,
    boolean abstained,
    String abstainReason,
    SearchResponse retrieval,
    TimingBreakdownDto timing,
    Instant generatedAt) {
  public AnswerResponse {
    citations = List.copyOf(citations);
  }
}
