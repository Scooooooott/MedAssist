package com.medassist.retrieval.api.dto;

import com.medassist.retrieval.application.model.RetrievalMode;
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
    RetrievalMode retrievalMode,
    boolean rerankEnabled,
    boolean degraded,
    List<String> degradationReasons,
    TimingBreakdownDto timing,
    Instant generatedAt) {
  public SearchResponse {
    results = List.copyOf(results);
    degradationReasons = List.copyOf(degradationReasons);
  }
}
