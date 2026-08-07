package com.medassist.retrieval.api.dto;

public record AnswerRequest(
    String query,
    Integer topK,
    RetrievalFiltersDto filters,
    String role,
    String modelName,
    String modelVersion) {}
