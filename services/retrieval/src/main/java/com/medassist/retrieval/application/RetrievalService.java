package com.medassist.retrieval.application;

import com.medassist.common.resilience.Degradation;
import com.medassist.common.resilience.DegradationRecorder;
import com.medassist.common.resilience.FallbackMode;
import com.medassist.common.resilience.ResilienceComponent;
import com.medassist.common.resilience.ResilienceExecutor;
import com.medassist.retrieval.api.dto.RetrievalFiltersDto;
import com.medassist.retrieval.api.dto.SearchRequest;
import com.medassist.retrieval.application.model.RetrievalFilters;
import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchOutcome;
import com.medassist.retrieval.application.model.SearchQuery;
import com.medassist.retrieval.config.RetrievalProperties;
import com.medassist.retrieval.model.ModelVersionMismatchException;
import com.medassist.retrieval.model.QueryEmbedding;
import com.medassist.retrieval.model.QueryEmbeddingClient;
import com.medassist.retrieval.repository.LexicalSearchRepository;
import com.medassist.retrieval.repository.RankedChunk;
import com.medassist.retrieval.repository.VectorSearchRepository;
import com.medassist.retrieval.rerank.RerankingResult;
import com.medassist.retrieval.rerank.RerankingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RetrievalService {
  private final RetrievalProperties properties;
  private final QueryEmbeddingClient embeddingClient;
  private final VectorSearchRepository vectorSearchRepository;
  private final LexicalSearchRepository lexicalSearchRepository;
  private final RrfFusion rrfFusion;
  private final RerankingService rerankingService;
  private final ExecutorService retrievalExecutor;
  private final DegradationRecorder degradationRecorder;
  private final ResilienceExecutor resilienceExecutor;

  @Autowired
  public RetrievalService(
      final RetrievalProperties properties,
      final QueryEmbeddingClient embeddingClient,
      final VectorSearchRepository vectorSearchRepository,
      final LexicalSearchRepository lexicalSearchRepository,
      final RrfFusion rrfFusion,
      final RerankingService rerankingService,
      @Qualifier("retrievalExecutor") final ExecutorService retrievalExecutor,
      final Optional<DegradationRecorder> degradationRecorder,
      final ResilienceExecutor resilienceExecutor) {
    this.properties = properties;
    this.embeddingClient = embeddingClient;
    this.vectorSearchRepository = vectorSearchRepository;
    this.lexicalSearchRepository = lexicalSearchRepository;
    this.rrfFusion = rrfFusion;
    this.rerankingService = rerankingService;
    this.retrievalExecutor = retrievalExecutor;
    this.degradationRecorder = degradationRecorder.orElseGet(DegradationRecorder::noop);
    this.resilienceExecutor = resilienceExecutor;
  }

  public SearchOutcome search(final SearchRequest request) {
    final long started = System.nanoTime();
    final SearchQuery query = normalize(request);
    return switch (query.retrievalMode()) {
      case VECTOR_ONLY -> vectorOnly(query, started);
      case LEXICAL_ONLY -> lexicalOnly(query, started);
      case HYBRID -> hybrid(query, started);
    };
  }

  private SearchOutcome vectorOnly(final SearchQuery query, final long started) {
    final VectorBranch branch = vectorBranch(query);
    return finish(
        query,
        branch.chunks(),
        branch.embeddingMs(),
        branch.retrievalMs(),
        false,
        List.of(),
        List.of());
  }

  private SearchOutcome lexicalOnly(final SearchQuery query, final long started) {
    final List<RetrievedChunk> candidates =
        resilienceExecutor
            .execute(
                ResilienceComponent.LEXICAL_RETRIEVAL,
                true,
                () ->
                    lexicalSearchRepository.search(
                        withCandidateTopN(query, lexicalCandidateTopN(query))))
            .stream()
            .map(RankedChunk::chunk)
            .toList();
    return finish(query, candidates, 0L, elapsedMillis(started), false, List.of(), List.of());
  }

  private SearchOutcome hybrid(final SearchQuery query, final long started) {
    final long deadline = started + properties.getRetrievalTimeout().toNanos();
    final CompletableFuture<VectorBranch> vectorFuture =
        CompletableFuture.supplyAsync(() -> vectorBranch(query), retrievalExecutor);
    final CompletableFuture<List<RankedChunk>> lexicalFuture =
        CompletableFuture.supplyAsync(
            () ->
                resilienceExecutor.execute(
                    ResilienceComponent.LEXICAL_RETRIEVAL,
                    true,
                    () ->
                        lexicalSearchRepository.search(
                            withCandidateTopN(query, lexicalCandidateTopN(query)))),
            retrievalExecutor);

    final VectorBranch vector;
    try {
      vector = getBeforeDeadline(vectorFuture, deadline);
    } catch (final ModelVersionMismatchException exception) {
      lexicalFuture.cancel(true);
      throw exception;
    } catch (final RuntimeException exception) {
      lexicalFuture.cancel(true);
      throw exception;
    }

    final List<RankedChunk> lexical;
    final List<String> degradationReasons = new ArrayList<>();
    final List<Degradation> degradations = new ArrayList<>();
    try {
      lexical = getBeforeDeadline(lexicalFuture, deadline);
    } catch (final RuntimeException exception) {
      lexicalFuture.cancel(true);
      final Degradation degradation =
          new Degradation(
              "LEXICAL_CHANNEL_FAILED",
              "LEXICAL_RETRIEVAL",
              FallbackMode.VECTOR_RESULTS,
              "lexical retrieval unavailable; vector results retained");
      degradationReasons.add(degradation.code());
      degradations.add(degradation);
      degradationRecorder.record(ResilienceComponent.LEXICAL_RETRIEVAL, degradation);
      return finish(
          query,
          vector.chunks(),
          vector.embeddingMs(),
          elapsedMillis(started),
          true,
          degradationReasons,
          degradations);
    }

    final List<RankedChunk> rankedVector = new ArrayList<>();
    for (int index = 0; index < vector.chunks().size(); index++) {
      final RetrievedChunk chunk = vector.chunks().get(index);
      rankedVector.add(new RankedChunk(chunk, index + 1, chunk.score()));
    }
    final List<RetrievedChunk> fused =
        rrfFusion.fuse(
            rankedVector,
            lexical,
            query.candidateTopN(),
            properties.getRrfK(),
            properties.getVectorWeight(),
            properties.getLexicalWeight());
    return finish(
        query, fused, vector.embeddingMs(), elapsedMillis(started), false, List.of(), List.of());
  }

  private SearchOutcome finish(
      final SearchQuery query,
      final List<RetrievedChunk> candidates,
      final long embeddingMs,
      final long retrievalMs,
      final boolean degraded,
      final List<String> degradationReasons,
      final List<Degradation> degradations) {
    final RerankingResult reranked =
        rerankingService.rerank(
            query.query(),
            candidates,
            query.topK(),
            query.rerankEnabled(),
            properties.getRerank().getModelName(),
            properties.getRerank().getTimeout());
    final List<String> reasons = new ArrayList<>(degradationReasons);
    final List<Degradation> structured = new ArrayList<>(degradations);
    if (reranked.degraded()) {
      final Degradation degradation =
          new Degradation(
              "RERANK_" + reranked.reason(),
              "RERANK",
              FallbackMode.ORIGINAL_ORDER,
              "reranker unavailable; original retrieval order retained");
      reasons.add(degradation.code());
      structured.add(degradation);
      degradationRecorder.record(ResilienceComponent.RERANK, degradation);
    }
    return new SearchOutcome(
        query,
        reranked.chunks(),
        embeddingMs,
        retrievalMs,
        degraded || reranked.degraded(),
        reasons,
        structured);
  }

  private VectorBranch vectorBranch(final SearchQuery query) {
    final QueryEmbedding embedding =
        resilienceExecutor.execute(
            ResilienceComponent.EMBEDDING,
            true,
            () -> embeddingClient.embed(query.query(), query.modelName(), query.modelVersion()));
    final long retrievalStarted = System.nanoTime();
    final List<RetrievedChunk> chunks =
        resilienceExecutor.execute(
            ResilienceComponent.VECTOR_RETRIEVAL,
            true,
            () ->
                vectorSearchRepository.search(
                    withCandidateTopN(query, vectorCandidateTopN(query)), embedding));
    return new VectorBranch(
        chunks, embedding.elapsedMs(), (System.nanoTime() - retrievalStarted) / 1_000_000L);
  }

  private int vectorCandidateTopN(final SearchQuery query) {
    return Math.max(
        query.topK(), Math.min(query.candidateTopN(), properties.getVectorCandidateTopN()));
  }

  private int lexicalCandidateTopN(final SearchQuery query) {
    return Math.max(
        query.topK(), Math.min(query.candidateTopN(), properties.getLexicalCandidateTopN()));
  }

  private SearchQuery withCandidateTopN(final SearchQuery query, final int candidateTopN) {
    return new SearchQuery(
        query.query(),
        query.topK(),
        candidateTopN,
        query.filters(),
        query.role(),
        query.modelName(),
        query.modelVersion(),
        query.distanceMetric(),
        query.retrievalMode(),
        query.rerankEnabled(),
        query.includeSuperseded(),
        query.contextualRetrievalMode(),
        query.chunkingStrategyId(),
        query.stalenessYears());
  }

  private <T> T getBeforeDeadline(final CompletableFuture<T> future, final long deadline) {
    final long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      future.cancel(true);
      throw new RetrievalDeadlineExceededException("retrieval deadline exceeded", null);
    }
    try {
      return future.get(remaining, TimeUnit.NANOSECONDS);
    } catch (final TimeoutException exception) {
      future.cancel(true);
      throw new RetrievalDeadlineExceededException("retrieval deadline exceeded", exception);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("retrieval interrupted", exception);
    } catch (final ExecutionException exception) {
      final Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("retrieval branch failed", cause);
    }
  }

  private long elapsedMillis(final long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }

  private record VectorBranch(List<RetrievedChunk> chunks, long embeddingMs, long retrievalMs) {
    private VectorBranch {
      chunks = List.copyOf(chunks);
    }
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
    final int requestedCandidateTopN =
        effectiveRequest.candidateTopN() == null
            ? properties.getDefaultCandidateTopN()
            : effectiveRequest.candidateTopN();
    final int candidateTopN =
        Math.max(topK, Math.min(requestedCandidateTopN, properties.getMaxCandidateTopN()));
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
        candidateTopN,
        toFilters(effectiveRequest.filters()),
        role,
        modelName,
        modelVersion,
        properties.getDistanceMetric(),
        effectiveRequest.retrievalMode() == null
            ? properties.getDefaultRetrievalMode()
            : effectiveRequest.retrievalMode(),
        effectiveRequest.rerankEnabled() == null
            ? properties.isDefaultRerankEnabled()
            : effectiveRequest.rerankEnabled(),
        Boolean.TRUE.equals(effectiveRequest.includeSuperseded()),
        effectiveRequest.contextualRetrievalMode() == null
            ? properties.getDefaultContextualRetrievalMode()
            : effectiveRequest.contextualRetrievalMode(),
        StringUtils.hasText(effectiveRequest.chunkingStrategyId())
            ? effectiveRequest.chunkingStrategyId()
            : properties.getDefaultChunkingStrategyId(),
        properties.getStalenessYears());
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
