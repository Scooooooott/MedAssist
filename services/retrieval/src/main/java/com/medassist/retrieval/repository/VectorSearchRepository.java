package com.medassist.retrieval.repository;

import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchQuery;
import com.medassist.retrieval.model.QueryEmbedding;
import java.util.List;

public interface VectorSearchRepository {
  List<RetrievedChunk> search(SearchQuery query, QueryEmbedding embedding);
}
