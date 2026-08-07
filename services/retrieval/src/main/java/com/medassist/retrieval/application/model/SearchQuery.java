package com.medassist.retrieval.application.model;

public record SearchQuery(
    String query,
    int topK,
    RetrievalFilters filters,
    String role,
    String modelName,
    String modelVersion,
    String distanceMetric) {}
