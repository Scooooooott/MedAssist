package com.medassist.retrieval.api.dto;

import java.time.Instant;
import java.util.List;

public record SearchResponse(
    String query,
    String role,
    String modelName,
    String modelVersion,
    String distanceMetric,
    RetrievalFiltersDto filters,
    List<RetrievalResultDto> results,
    TimingBreakdownDto timing,
    Instant generatedAt) {
  public SearchResponse {
    results = List.copyOf(results);
  }
}
