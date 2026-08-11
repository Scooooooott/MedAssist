package com.medassist.retrieval.rerank;

import java.util.List;

/** Backend response kept separate from the source chunk metadata. */
public record RerankClientResponse(
    List<RerankScore> results, String modelName, String modelVersion) {}
