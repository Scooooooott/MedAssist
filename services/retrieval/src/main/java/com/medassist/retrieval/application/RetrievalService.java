package com.medassist.retrieval.application;

import com.medassist.retrieval.api.dto.RetrievalFiltersDto;
import com.medassist.retrieval.api.dto.SearchRequest;
import com.medassist.retrieval.application.model.RetrievalFilters;
import com.medassist.retrieval.application.model.SearchOutcome;
import com.medassist.retrieval.application.model.SearchQuery;
import com.medassist.retrieval.config.RetrievalProperties;
import com.medassist.retrieval.model.QueryEmbedding;
import com.medassist.retrieval.model.QueryEmbeddingClient;
import com.medassist.retrieval.repository.VectorSearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RetrievalService {
  private final RetrievalProperties properties;
  private final QueryEmbeddingClient embeddingClient;
  private final VectorSearchRepository vectorSearchRepository;

  public RetrievalService(
      final RetrievalProperties properties,
      final QueryEmbeddingClient embeddingClient,
      final VectorSearchRepository vectorSearchRepository) {
    this.properties = properties;
    this.embeddingClient = embeddingClient;
    this.vectorSearchRepository = vectorSearchRepository;
  }

  public SearchOutcome search(final SearchRequest request) {
    final long started = System.nanoTime();
    final SearchQuery query = normalize(request);
    final QueryEmbedding embedding =
        embeddingClient.embed(query.query(), query.modelName(), query.modelVersion());
    final long retrievalStarted = System.nanoTime();
    final var chunks = vectorSearchRepository.search(query, embedding);
    final long retrievalMs = (System.nanoTime() - retrievalStarted) / 1_000_000L;
    final long totalRetrievalMs = Math.max(retrievalMs, (System.nanoTime() - started) / 1_000_000L);
    return new SearchOutcome(query, chunks, embedding.elapsedMs(), totalRetrievalMs);
  }

  private SearchQuery normalize(final SearchRequest request) {
    final SearchRequest effectiveRequest =
        request == null ? new SearchRequest("", null, null, null, null, null) : request;
    final String queryText = effectiveRequest.query();
    if (!StringUtils.hasText(queryText)) {
      throw new IllegalArgumentException("query is required");
    }
    final int requestedTopK =
        effectiveRequest.topK() == null ? properties.getDefaultTopK() : effectiveRequest.topK();
    final int topK = Math.max(1, Math.min(requestedTopK, properties.getMaxTopK()));
    final String role =
        StringUtils.hasText(effectiveRequest.role()) ? effectiveRequest.role() : "CLINICIAN";
    final String modelName =
        StringUtils.hasText(effectiveRequest.modelName())
            ? effectiveRequest.modelName()
            : properties.getDefaultModelName();
    final String modelVersion =
        StringUtils.hasText(effectiveRequest.modelVersion())
            ? effectiveRequest.modelVersion()
            : properties.getDefaultModelVersion();
    return new SearchQuery(
        queryText.trim(),
        topK,
        toFilters(effectiveRequest.filters()),
        role,
        modelName,
        modelVersion,
        properties.getDistanceMetric());
  }

  private RetrievalFilters toFilters(final RetrievalFiltersDto filters) {
    if (filters == null) {
      return new RetrievalFilters(null, null, null, null, null);
    }
    return new RetrievalFilters(
        filters.docTypes(),
        filters.publishers(),
        filters.effectiveDateFrom(),
        filters.effectiveDateTo(),
        filters.sectionTypes());
  }
}
