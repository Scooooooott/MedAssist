package com.medassist.retrieval.rerank;

import com.medassist.retrieval.application.model.RetrievedChunk;
import java.time.Duration;
import java.util.List;

/** Port for a reranker backend. */
public interface RerankClient {
  RerankClientResponse rerank(
      String query, List<RetrievedChunk> candidates, String modelName, Duration timeout);
}
