package com.medassist.retrieval.application;

import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.AnswerResponse;
import com.medassist.retrieval.api.dto.CitationDto;
import com.medassist.retrieval.api.dto.SearchRequest;
import com.medassist.retrieval.api.dto.SearchResponse;
import com.medassist.retrieval.api.dto.TimingBreakdownDto;
import com.medassist.retrieval.application.model.CitationCandidate;
import com.medassist.retrieval.application.model.CitationValidationResult;
import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchOutcome;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AnswerService {
  private final RetrievalService retrievalService;
  private final CitationValidator citationValidator;
  private final RetrievalResponseMapper mapper;

  public AnswerService(
      final RetrievalService retrievalService,
      final CitationValidator citationValidator,
      final RetrievalResponseMapper mapper) {
    this.retrievalService = retrievalService;
    this.citationValidator = citationValidator;
    this.mapper = mapper;
  }

  // TEMPORARY: moves to agent-service in M3
  public AnswerResponse answer(final AnswerRequest request) {
    final long started = System.nanoTime();
    final SearchRequest searchRequest =
        new SearchRequest(
            request.query(),
            request.topK(),
            request.filters(),
            request.role(),
            request.modelName(),
            request.modelVersion());
    final SearchOutcome outcome = retrievalService.search(searchRequest);
    final List<CitationCandidate> candidates =
        outcome.chunks().stream().findFirst().map(this::citationFrom).stream().toList();
    final List<CitationValidationResult> validationResults =
        citationValidator.validate(candidates, outcome.chunks());
    final List<CitationDto> citations =
        validationResults.stream().map(this::toDto).filter(CitationDto::valid).toList();
    final boolean sufficientEvidence = !citations.isEmpty();
    final String answerText =
        sufficientEvidence
            ? "The retrieved corpus contains relevant evidence. Review the cited source text before using this information."
            : "I do not have enough cited evidence in the retrieved corpus to answer safely. Please consult a qualified medical professional.";
    final long totalMs = (System.nanoTime() - started) / 1_000_000L;
    final SearchResponse retrieval = mapper.toResponse(outcome);
    return new AnswerResponse(
        request.query(),
        answerText,
        citations,
        sufficientEvidence,
        !sufficientEvidence,
        sufficientEvidence ? "" : "No valid citation survived M1 existence checks.",
        retrieval,
        new TimingBreakdownDto(outcome.embeddingMs(), outcome.retrievalMs(), 0L, totalMs),
        Instant.now());
  }

  private CitationCandidate citationFrom(final RetrievedChunk chunk) {
    final String quotedSpan =
        chunk.text().length() <= 240 ? chunk.text() : chunk.text().substring(0, 240);
    return new CitationCandidate(
        chunk.chunkId(), chunk.documentVersionId(), quotedSpan, "top retrieved chunk");
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
}
