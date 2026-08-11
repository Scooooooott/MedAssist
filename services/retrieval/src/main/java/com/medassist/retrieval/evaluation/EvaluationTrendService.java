package com.medassist.retrieval.evaluation;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class EvaluationTrendService {
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  private final EvaluationTrendRepository repository;

  public EvaluationTrendService(final EvaluationTrendRepository repository) {
    this.repository = repository;
  }

  public List<EvaluationRunView> find(
      final String evalSetVersion, final String modelName, final Integer requestedLimit) {
    final int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new InvalidEvaluationQueryException("limit must be between 1 and 100");
    }
    return repository.find(
        new EvaluationTrendQuery(
            normalizeFilter(evalSetVersion), normalizeFilter(modelName), limit));
  }

  private String normalizeFilter(final String value) {
    if (value == null) {
      return null;
    }
    final String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > 200) {
      throw new InvalidEvaluationQueryException("evaluation filters must be 1 to 200 characters");
    }
    return normalized;
  }
}
