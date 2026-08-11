package com.medassist.retrieval.rerank;

/** One backend-provided score and rank for a candidate. */
public record RerankScore(String candidateId, double score, int rank) {}
