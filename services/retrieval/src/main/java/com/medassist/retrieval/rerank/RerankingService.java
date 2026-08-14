package com.medassist.retrieval.rerank;

import com.medassist.common.resilience.ResilienceComponent;
import com.medassist.common.resilience.ResilienceExecutor;
import com.medassist.retrieval.application.model.RetrievedChunk;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Applies a reranker while preserving source retrieval metadata and safe fallback behavior. */
public final class RerankingService {
  private final RerankClient client;
  private final ResilienceExecutor resilienceExecutor;

  public RerankingService(final RerankClient client, final ResilienceExecutor resilienceExecutor) {
    this.client = Objects.requireNonNull(client, "client");
    this.resilienceExecutor = Objects.requireNonNull(resilienceExecutor, "resilienceExecutor");
  }

  public RerankingResult rerank(
      final String query,
      final List<RetrievedChunk> fusedCandidates,
      final int topK,
      final boolean enabled,
      final String modelName,
      final Duration timeout) {
    validateInput(query, fusedCandidates, topK, modelName, timeout);
    final List<RetrievedChunk> originalTopK = limit(fusedCandidates, topK);
    if (!enabled) {
      return new RerankingResult(originalTopK, false, "DISABLED", modelName, null);
    }
    if (fusedCandidates.isEmpty()) {
      return new RerankingResult(List.of(), false, "NO_CANDIDATES", modelName, null);
    }
    try {
      indexCandidates(fusedCandidates);
    } catch (final IllegalArgumentException exception) {
      return degraded(originalTopK, "MALFORMED_CANDIDATES", modelName, null);
    }

    final RerankClientResponse response;
    try {
      response =
          resilienceExecutor.execute(
              ResilienceComponent.RERANK,
              true,
              () -> client.rerank(query, fusedCandidates, modelName, timeout));
    } catch (final RerankClientException exception) {
      return degraded(originalTopK, exception.reason().name(), modelName, null);
    } catch (final RuntimeException exception) {
      return degraded(originalTopK, "BACKEND_ERROR", modelName, null);
    }

    try {
      return successfulResult(fusedCandidates, topK, modelName, response);
    } catch (final IllegalArgumentException exception) {
      return degraded(originalTopK, "MALFORMED_RESPONSE", modelName, null);
    }
  }

  private static RerankingResult successfulResult(
      final List<RetrievedChunk> candidates,
      final int topK,
      final String requestedModelName,
      final RerankClientResponse response) {
    if (response == null
        || response.results() == null
        || response.modelName() == null
        || response.modelName().isBlank()
        || !requestedModelName.equals(response.modelName())) {
      throw new IllegalArgumentException("backend model identity is invalid");
    }
    final Map<UUID, RetrievedChunk> byId = indexCandidates(candidates);
    final Set<UUID> returnedIds = new HashSet<>();
    final Set<Integer> returnedRanks = new HashSet<>();
    final List<RankedCandidate> ranked = new ArrayList<>();
    for (final RerankScore result : response.results()) {
      if (result == null
          || result.candidateId() == null
          || result.candidateId().isBlank()
          || !Double.isFinite(result.score())
          || result.rank() < 1
          || result.rank() > candidates.size()) {
        throw new IllegalArgumentException("backend returned a malformed result");
      }
      final UUID candidateId;
      try {
        candidateId = UUID.fromString(result.candidateId());
      } catch (final IllegalArgumentException exception) {
        throw new IllegalArgumentException("backend returned an invalid candidate id", exception);
      }
      if (!byId.containsKey(candidateId)
          || !returnedIds.add(candidateId)
          || !returnedRanks.add(result.rank())) {
        throw new IllegalArgumentException("backend returned duplicate or unknown candidates");
      }
      ranked.add(new RankedCandidate(byId.get(candidateId), result));
    }
    if (returnedIds.size() != candidates.size()) {
      throw new IllegalArgumentException("backend omitted candidates");
    }
    ranked.sort(
        Comparator.comparingInt((RankedCandidate candidate) -> candidate.score().rank())
            .thenComparing(
                Comparator.comparingDouble((RankedCandidate candidate) -> candidate.score().score())
                    .reversed())
            .thenComparing(candidate -> candidate.chunk().chunkId().toString()));
    return new RerankingResult(
        ranked.stream().limit(topK).map(RankedCandidate::chunk).toList(),
        false,
        "OK",
        response.modelName(),
        response.modelVersion());
  }

  private static Map<UUID, RetrievedChunk> indexCandidates(final List<RetrievedChunk> candidates) {
    final Map<UUID, RetrievedChunk> byId = new HashMap<>();
    for (final RetrievedChunk candidate : candidates) {
      if (candidate == null || candidate.chunkId() == null || candidate.text() == null) {
        throw new IllegalArgumentException("candidate id and text are required");
      }
      if (byId.put(candidate.chunkId(), candidate) != null) {
        throw new IllegalArgumentException("candidate ids must be unique");
      }
    }
    return byId;
  }

  private static void validateInput(
      final String query,
      final List<RetrievedChunk> candidates,
      final int topK,
      final String modelName,
      final Duration timeout) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query is required");
    }
    if (candidates == null) {
      throw new IllegalArgumentException("fused candidates are required");
    }
    if (topK < 1) {
      throw new IllegalArgumentException("topK must be positive");
    }
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("model name is required");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  private static List<RetrievedChunk> limit(final List<RetrievedChunk> candidates, final int topK) {
    return candidates.stream().limit(topK).toList();
  }

  private static RerankingResult degraded(
      final List<RetrievedChunk> originalTopK,
      final String reason,
      final String modelName,
      final String modelVersion) {
    return new RerankingResult(originalTopK, true, reason, modelName, modelVersion);
  }

  private record RankedCandidate(RetrievedChunk chunk, RerankScore score) {}
}
