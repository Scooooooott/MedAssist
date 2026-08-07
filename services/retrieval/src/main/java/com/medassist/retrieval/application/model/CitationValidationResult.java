package com.medassist.retrieval.application.model;

public record CitationValidationResult(CitationCandidate citation, boolean valid, String message) {}
