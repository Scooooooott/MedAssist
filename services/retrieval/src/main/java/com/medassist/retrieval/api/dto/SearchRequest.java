package com.medassist.retrieval.api.dto;

import com.medassist.retrieval.application.model.ContextualRetrievalMode;
import com.medassist.retrieval.application.model.RetrievalMode;

public record SearchRequest(
    String query,
    Integer topK,
    RetrievalFiltersDto filters,
    String role,
    String modelName,
    String modelVersion,
    RetrievalMode retrievalMode,
    Boolean rerankEnabled,
    Boolean includeSuperseded,
    ContextualRetrievalMode contextualRetrievalMode,
    String chunkingStrategyId,
    Integer candidateTopN) {
  public SearchRequest(
      final String query,
      final Integer topK,
      final RetrievalFiltersDto filters,
      final String role,
      final String modelName,
      final String modelVersion) {
    this(query, topK, filters, role, modelName, modelVersion, null, null, null, null, null, null);
  }

  public SearchRequest withRole(final String authenticatedRole) {
    return new SearchRequest(
        query,
        topK,
        filters,
        authenticatedRole,
        modelName,
        modelVersion,
        retrievalMode,
        rerankEnabled,
        includeSuperseded,
        contextualRetrievalMode,
        chunkingStrategyId,
        candidateTopN);
  }
}
