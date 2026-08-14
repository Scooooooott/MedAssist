package com.medassist.retrieval.application;

import com.medassist.retrieval.api.dto.RetrievalFiltersDto;
import com.medassist.retrieval.api.dto.RetrievalResultDto;
import com.medassist.retrieval.api.dto.SearchResponse;
import com.medassist.retrieval.api.dto.TimingBreakdownDto;
import com.medassist.retrieval.application.model.RetrievalFilters;
import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchOutcome;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class RetrievalResponseMapper {
  public SearchResponse toResponse(final SearchOutcome outcome) {
    return new SearchResponse(
        outcome.query().query(),
        outcome.query().role(),
        outcome.query().modelName(),
        outcome.query().modelVersion(),
        outcome.query().distanceMetric(),
        toDto(outcome.query().filters()),
        outcome.chunks().stream().map(this::toDto).toList(),
        outcome.query().retrievalMode(),
        outcome.query().rerankEnabled(),
        outcome.degraded(),
        outcome.degradationReasons(),
        outcome.degradations(),
        new TimingBreakdownDto(
            outcome.embeddingMs(), outcome.retrievalMs(), 0L, outcome.retrievalMs()),
        Instant.now());
  }

  private RetrievalFiltersDto toDto(final RetrievalFilters filters) {
    return new RetrievalFiltersDto(
        filters.docTypes(),
        filters.publishers(),
        filters.effectiveDateFrom(),
        filters.effectiveDateTo(),
        filters.sectionTypes());
  }

  private RetrievalResultDto toDto(final RetrievedChunk chunk) {
    return new RetrievalResultDto(
        chunk.chunkId(),
        chunk.documentVersionId(),
        chunk.ordinal(),
        chunk.sectionPath(),
        chunk.text(),
        chunk.tokenCount(),
        chunk.sourceCharStart(),
        chunk.sourceCharEnd(),
        chunk.score(),
        chunk.retrievalMethod(),
        chunk.distanceMetric(),
        chunk.docType(),
        chunk.publisher(),
        chunk.sourceTitle(),
        chunk.version(),
        chunk.effectiveDate(),
        chunk.documentStatus(),
        chunk.stale(),
        chunk.vectorRank(),
        chunk.lexicalRank(),
        chunk.vectorScore(),
        chunk.lexicalScore(),
        chunk.fusedScore(),
        chunk.metadata());
  }
}
