package com.medassist.retrieval.application.model;

public record SearchQuery(
    String query,
    int topK,
    int candidateTopN,
    RetrievalFilters filters,
    String role,
    String modelName,
    String modelVersion,
    String distanceMetric,
    RetrievalMode retrievalMode,
    boolean rerankEnabled,
    boolean includeSuperseded,
    ContextualRetrievalMode contextualRetrievalMode,
    String chunkingStrategyId,
    int stalenessYears) {}
