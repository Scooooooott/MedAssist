package com.medassist.retrieval.cache;

import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.RetrievalFiltersDto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class CacheKeyFactory {
  private final QueryNormalizer normalizer;

  public CacheKeyFactory(final QueryNormalizer normalizer) {
    this.normalizer = normalizer;
  }

  public String embeddingKey(
      final String prefix, final String query, final String modelName, final String modelVersion) {
    return prefix
        + "embedding:"
        + hash(normalizer.normalize(query) + "|" + safe(modelName) + "|" + safe(modelVersion));
  }

  public String answerKey(final String prefix, final AnswerRequest request) {
    final RetrievalFiltersDto filters = request.filters();
    final String canonical =
        normalizer.normalize(request.query())
            + "|topK="
            + safe(request.topK())
            + "|candidateTopN="
            + safe(request.candidateTopN())
            + "|doc="
            + sorted(filters == null ? Set.of() : filters.docTypes())
            + "|publisher="
            + sorted(filters == null ? Set.of() : filters.publishers())
            + "|from="
            + (filters == null ? "" : safe(filters.effectiveDateFrom()))
            + "|to="
            + (filters == null ? "" : safe(filters.effectiveDateTo()))
            + "|section="
            + sorted(filters == null ? Set.of() : filters.sectionTypes())
            + "|mode="
            + safe(request.retrievalMode())
            + "|rerank="
            + Boolean.TRUE.equals(request.rerankEnabled())
            + "|role="
            + safe(request.role())
            + "|model="
            + safe(request.modelName())
            + "|version="
            + safe(request.modelVersion())
            + "|superseded="
            + Boolean.TRUE.equals(request.includeSuperseded())
            + "|context="
            + safe(request.contextualRetrievalMode())
            + "|strategy="
            + safe(request.chunkingStrategyId());
    return prefix + "answer:" + hash(canonical);
  }

  private String sorted(final Set<String> values) {
    return values.stream().sorted().collect(Collectors.joining(","));
  }

  private String safe(final Object value) {
    return value == null ? "" : value.toString();
  }

  private String hash(final String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
