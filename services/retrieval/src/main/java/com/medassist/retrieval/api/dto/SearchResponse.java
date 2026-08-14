package com.medassist.retrieval.api.dto;

import com.medassist.common.resilience.Degradation;
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
    List<Degradation> degradations,
    TimingBreakdownDto timing,
    Instant generatedAt) {
  public SearchResponse {
    results = List.copyOf(results);
    degradationReasons = List.copyOf(degradationReasons);
    degradations = List.copyOf(degradations);
  }

  public SearchResponse(
      final String query,
      final String role,
      final String modelName,
      final String modelVersion,
      final String distanceMetric,
      final RetrievalFiltersDto filters,
      final List<RetrievalResultDto> results,
      final RetrievalMode retrievalMode,
      final boolean rerankEnabled,
      final boolean degraded,
      final List<String> degradationReasons,
      final TimingBreakdownDto timing,
      final Instant generatedAt) {
    this(
        query,
        role,
        modelName,
        modelVersion,
        distanceMetric,
        filters,
        results,
        retrievalMode,
        rerankEnabled,
        degraded,
        degradationReasons,
        List.of(),
        timing,
        generatedAt);
  }
}
