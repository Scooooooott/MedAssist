package com.medassist.retrieval.application;

import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.AnswerResponse;
import com.medassist.retrieval.api.dto.CitationDto;
import com.medassist.retrieval.api.dto.SearchRequest;
import com.medassist.retrieval.api.dto.SearchResponse;
import com.medassist.retrieval.api.dto.TimingBreakdownDto;
import com.medassist.retrieval.application.generation.AnswerGenerator;
import com.medassist.retrieval.application.generation.GeneratedAnswer;
import com.medassist.retrieval.application.generation.GeneratedCitation;
import com.medassist.retrieval.application.generation.GenerationEvent;
import com.medassist.retrieval.application.model.CitationCandidate;
import com.medassist.retrieval.application.model.CitationValidationResult;
import com.medassist.retrieval.application.model.SearchOutcome;
import com.medassist.retrieval.cache.AnswerResponseCache;
import com.medassist.retrieval.config.RetrievalProperties;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class AnswerService {
  private final RetrievalService retrievalService;
  private final CitationValidator citationValidator;
  private final RetrievalResponseMapper mapper;
  private final AnswerResponseCache responseCache;
  private final AnswerGenerator answerGenerator;
  private final RetrievalProperties properties;

  public AnswerService(
      final RetrievalService retrievalService,
      final CitationValidator citationValidator,
      final RetrievalResponseMapper mapper,
      final AnswerResponseCache responseCache,
      final AnswerGenerator answerGenerator,
      final RetrievalProperties properties) {
    this.retrievalService = retrievalService;
    this.citationValidator = citationValidator;
    this.mapper = mapper;
    this.responseCache = responseCache;
    this.answerGenerator = answerGenerator;
    this.properties = properties;
  }

  // TEMPORARY: moves to agent-service in M3.
  public AnswerResponse answer(final AnswerRequest request) {
    return responseCache.getOrCompute(request, () -> answerUncached(request));
  }

  public Flux<AnswerStreamEvent> stream(final AnswerRequest request) {
    return Flux.defer(
        () -> {
          final long started = System.nanoTime();
          final SearchOutcome outcome = retrievalService.search(toSearchRequest(request));
          final int initialTopK = effectiveTopK(request);
          if (outcome.chunks().isEmpty() && initialTopK < properties.getMaxTopK()) {
            final int retryTopK =
                Math.min(properties.getMaxTopK(), Math.max(initialTopK + 1, initialTopK * 2));
            final SearchOutcome retryOutcome =
                retrievalService.search(toSearchRequest(request, retryTopK));
            return Flux.concat(
                Flux.just(
                    AnswerStreamEvent.retry(
                        1, 2, "No evidence was returned; expanding the retrieval window.")),
                streamOutcome(request, retryOutcome, started));
          }
          return streamOutcome(request, outcome, started);
        });
  }

  private Flux<AnswerStreamEvent> streamOutcome(
      final AnswerRequest request, final SearchOutcome outcome, final long started) {
    if (outcome.chunks().isEmpty()) {
      return Flux.just(AnswerStreamEvent.complete(abstain(request, outcome, started, 0L)));
    }
    final long generationStarted = System.nanoTime();
    return answerGenerator.stream(request.query(), outcome.chunks())
        .collectList()
        .flatMapMany(
            events -> {
              final GeneratedAnswer generated = finalAnswer(events);
              final long generationMs = elapsedMillis(generationStarted);
              final AnswerResponse response =
                  response(request, outcome, generated, started, generationMs);
              if (response.abstained()) {
                return Flux.just(AnswerStreamEvent.complete(response));
              }
              final Flux<AnswerStreamEvent> deltas =
                  Flux.fromIterable(events)
                      .filter(GenerationEvent::isDelta)
                      .map(GenerationEvent::delta)
                      .filter(delta -> !delta.isEmpty())
                      .map(AnswerStreamEvent::delta);
              return deltas.concatWithValues(AnswerStreamEvent.complete(response));
            });
  }

  private AnswerResponse answerUncached(final AnswerRequest request) {
    final long started = System.nanoTime();
    final SearchOutcome outcome = retrievalService.search(toSearchRequest(request));
    if (outcome.chunks().isEmpty()) {
      return abstain(request, outcome, started, 0L);
    }
    final long generationStarted = System.nanoTime();
    final GeneratedAnswer generated = answerGenerator.generate(request.query(), outcome.chunks());
    return response(request, outcome, generated, started, elapsedMillis(generationStarted));
  }

  private SearchRequest toSearchRequest(final AnswerRequest request) {
    return toSearchRequest(request, request.topK());
  }

  private SearchRequest toSearchRequest(final AnswerRequest request, final Integer topK) {
    return new SearchRequest(
        request.query(),
        topK,
        request.filters(),
        request.role(),
        request.modelName(),
        request.modelVersion(),
        request.retrievalMode(),
        request.rerankEnabled(),
        request.includeSuperseded(),
        request.contextualRetrievalMode(),
        request.chunkingStrategyId(),
        request.candidateTopN());
  }

  private int effectiveTopK(final AnswerRequest request) {
    return request.topK() == null ? properties.getDefaultTopK() : request.topK();
  }

  private AnswerResponse response(
      final AnswerRequest request,
      final SearchOutcome outcome,
      final GeneratedAnswer generated,
      final long started,
      final long generationMs) {
    final List<CitationCandidate> candidates =
        generated.citations().stream().map(this::citationFrom).toList();
    final List<CitationDto> citations =
        citationValidator.validate(candidates, outcome.chunks()).stream()
            .map(this::toDto)
            .filter(CitationDto::valid)
            .toList();
    final boolean sufficientEvidence = generated.sufficientEvidence() && !citations.isEmpty();
    final String reason =
        sufficientEvidence
            ? ""
            : generated.sufficientEvidence()
                ? "No valid citation survived M1 existence checks."
                : "The generation model reported insufficient evidence.";
    return buildResponse(
        request,
        outcome,
        sufficientEvidence ? generated.answer() : properties.getAbstainMessage(),
        citations,
        sufficientEvidence,
        reason,
        started,
        generationMs);
  }

  private AnswerResponse abstain(
      final AnswerRequest request,
      final SearchOutcome outcome,
      final long started,
      final long generationMs) {
    return buildResponse(
        request,
        outcome,
        properties.getAbstainMessage(),
        List.of(),
        false,
        "No retrievable evidence was available.",
        started,
        generationMs);
  }

  private AnswerResponse buildResponse(
      final AnswerRequest request,
      final SearchOutcome outcome,
      final String answer,
      final List<CitationDto> citations,
      final boolean sufficientEvidence,
      final String abstainReason,
      final long started,
      final long generationMs) {
    final long totalMs = elapsedMillis(started);
    final SearchResponse retrieval = mapper.toResponse(outcome);
    return new AnswerResponse(
        request.query(),
        answer,
        citations,
        sufficientEvidence,
        !sufficientEvidence,
        abstainReason,
        retrieval,
        new TimingBreakdownDto(outcome.embeddingMs(), outcome.retrievalMs(), generationMs, totalMs),
        Instant.now());
  }

  private GeneratedAnswer finalAnswer(final List<GenerationEvent> events) {
    return events.stream()
        .filter(GenerationEvent::isFinal)
        .reduce((first, second) -> second)
        .map(GenerationEvent::finalAnswer)
        .orElseThrow(com.medassist.retrieval.application.generation.AnswerGenerationException::new);
  }

  private CitationCandidate citationFrom(final GeneratedCitation citation) {
    return new CitationCandidate(
        citation.chunkId(),
        citation.documentVersionId(),
        citation.quotedSpan(),
        citation.relevance());
  }

  private CitationDto toDto(final CitationValidationResult result) {
    final CitationCandidate citation = result.citation();
    return new CitationDto(
        citation == null ? null : citation.chunkId(),
        citation == null ? null : citation.documentVersionId(),
        citation == null ? "" : citation.quotedSpan(),
        citation == null ? "" : citation.relevance(),
        result.valid(),
        result.message());
  }

  private long elapsedMillis(final long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }
}
