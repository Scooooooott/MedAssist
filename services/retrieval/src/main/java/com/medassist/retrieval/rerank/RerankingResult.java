package com.medassist.retrieval.rerank;

import com.medassist.retrieval.application.model.RetrievedChunk;
import java.util.List;

/** Reranking outcome, including an explicit degraded-mode explanation. */
public record RerankingResult(
    List<RetrievedChunk> chunks,
    boolean degraded,
    String reason,
    String modelName,
    String modelVersion) {}
