package com.medassist.retrieval.api.dto;

public record TimingBreakdownDto(
    long embeddingMs, long retrievalMs, long generationMs, long totalMs) {}
