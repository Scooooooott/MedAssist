package com.medassist.retrieval.grpc;

import com.medassist.contracts.v1.ContextualRetrievalMode;
import com.medassist.contracts.v1.DocumentMetadata;
import com.medassist.contracts.v1.RetrievalResult;
import com.medassist.contracts.v1.RetrievalServiceGrpc;
import com.medassist.contracts.v1.SearchResponse;
import com.medassist.contracts.v1.SourceRange;
import com.medassist.contracts.v1.TimingBreakdown;
import com.medassist.retrieval.api.dto.RetrievalFiltersDto;
import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchOutcome;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public final class RetrievalGrpcService extends RetrievalServiceGrpc.RetrievalServiceImplBase {
  private final com.medassist.retrieval.application.RetrievalService retrievalService;

  public RetrievalGrpcService(
      final com.medassist.retrieval.application.RetrievalService retrievalService) {
    this.retrievalService = retrievalService;
  }

  @Override
  public void search(
      final com.medassist.contracts.v1.SearchRequest request,
      final StreamObserver<SearchResponse> responseObserver) {
    try {
      final SearchOutcome outcome = retrievalService.search(toRequest(request));
      responseObserver.onNext(toResponse(outcome));
      responseObserver.onCompleted();
    } catch (final IllegalArgumentException exception) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription(exception.getMessage()).asRuntimeException());
    } catch (final RuntimeException exception) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("retrieval request failed").asRuntimeException());
    }
  }

  private com.medassist.retrieval.api.dto.SearchRequest toRequest(
      final com.medassist.contracts.v1.SearchRequest request) {
    return new com.medassist.retrieval.api.dto.SearchRequest(
        request.getQuery(),
        request.getTopK() > 0 ? request.getTopK() : null,
        new RetrievalFiltersDto(
            java.util.Set.copyOf(request.getFilters().getDocTypeList()),
            java.util.Set.copyOf(request.getFilters().getPublisherList()),
            parseDate(request.getFilters().getEffectiveDateFrom()),
            parseDate(request.getFilters().getEffectiveDateTo()),
            java.util.Set.copyOf(request.getFilters().getSectionTypeList())),
        request.getRole(),
        request.getModelName(),
        request.getModelVersion(),
        toMode(request.getRetrievalMode()),
        request.getRerankEnabled(),
        request.getIncludeSuperseded(),
        toContextMode(request.getContextualRetrievalMode()),
        request.getChunkingStrategyId(),
        request.getCandidateTopN() > 0 ? request.getCandidateTopN() : null);
  }

  private LocalDate parseDate(final String value) {
    return value == null || value.isBlank() ? null : LocalDate.parse(value);
  }

  private com.medassist.retrieval.application.model.RetrievalMode toMode(
      final com.medassist.contracts.v1.RetrievalMode mode) {
    return switch (mode) {
      case RETRIEVAL_MODE_VECTOR_ONLY ->
          com.medassist.retrieval.application.model.RetrievalMode.VECTOR_ONLY;
      case RETRIEVAL_MODE_LEXICAL_ONLY ->
          com.medassist.retrieval.application.model.RetrievalMode.LEXICAL_ONLY;
      case RETRIEVAL_MODE_HYBRID -> com.medassist.retrieval.application.model.RetrievalMode.HYBRID;
      case RETRIEVAL_MODE_UNSPECIFIED, UNRECOGNIZED -> null;
    };
  }

  private com.medassist.retrieval.application.model.ContextualRetrievalMode toContextMode(
      final ContextualRetrievalMode mode) {
    return switch (mode) {
      case CONTEXTUAL_RETRIEVAL_MODE_OFF ->
          com.medassist.retrieval.application.model.ContextualRetrievalMode.OFF;
      case CONTEXTUAL_RETRIEVAL_MODE_RULE_BASED ->
          com.medassist.retrieval.application.model.ContextualRetrievalMode.RULE_BASED;
      case CONTEXTUAL_RETRIEVAL_MODE_LLM_GENERATED ->
          com.medassist.retrieval.application.model.ContextualRetrievalMode.LLM_GENERATED;
      case CONTEXTUAL_RETRIEVAL_MODE_UNSPECIFIED, UNRECOGNIZED -> null;
    };
  }

  private SearchResponse toResponse(final SearchOutcome outcome) {
    final SearchResponse.Builder response =
        SearchResponse.newBuilder()
            .setQuery(outcome.query().query())
            .setRole(outcome.query().role())
            .setModelName(outcome.query().modelName())
            .setModelVersion(outcome.query().modelVersion())
            .setDistanceMetric(outcome.query().distanceMetric())
            .setRetrievalMode(toProtoMode(outcome.query().retrievalMode()))
            .setRerankEnabled(outcome.query().rerankEnabled())
            .setDegraded(outcome.degraded())
            .addAllDegradationReasons(outcome.degradationReasons())
            .addAllDegradations(outcome.degradations().stream().map(this::toDegradation).toList())
            .setTiming(
                TimingBreakdown.newBuilder()
                    .setEmbeddingMs(outcome.embeddingMs())
                    .setRetrievalMs(outcome.retrievalMs())
                    .setTotalMs(outcome.embeddingMs() + outcome.retrievalMs()))
            .setAppliedFilters(toFilters(outcome.query().filters()));
    outcome.chunks().stream().map(this::toResult).forEach(response::addResults);
    return response.build();
  }

  private com.medassist.contracts.v1.Degradation toDegradation(
      final com.medassist.common.resilience.Degradation degradation) {
    return com.medassist.contracts.v1.Degradation.newBuilder()
        .setCode(degradation.code())
        .setAffectedStage(degradation.affectedStage())
        .setFallbackMode(degradation.fallbackMode().name())
        .setReason(degradation.reason())
        .build();
  }

  private com.medassist.contracts.v1.RetrievalMode toProtoMode(
      final com.medassist.retrieval.application.model.RetrievalMode mode) {
    return switch (mode) {
      case VECTOR_ONLY -> com.medassist.contracts.v1.RetrievalMode.RETRIEVAL_MODE_VECTOR_ONLY;
      case LEXICAL_ONLY -> com.medassist.contracts.v1.RetrievalMode.RETRIEVAL_MODE_LEXICAL_ONLY;
      case HYBRID -> com.medassist.contracts.v1.RetrievalMode.RETRIEVAL_MODE_HYBRID;
    };
  }

  private com.medassist.contracts.v1.RetrievalFilters toFilters(
      final com.medassist.retrieval.application.model.RetrievalFilters filters) {
    final com.medassist.contracts.v1.RetrievalFilters.Builder builder =
        com.medassist.contracts.v1.RetrievalFilters.newBuilder()
            .addAllDocType(filters.docTypes())
            .addAllPublisher(filters.publishers())
            .addAllSectionType(filters.sectionTypes());
    if (filters.effectiveDateFrom() != null) {
      builder.setEffectiveDateFrom(filters.effectiveDateFrom().toString());
    }
    if (filters.effectiveDateTo() != null) {
      builder.setEffectiveDateTo(filters.effectiveDateTo().toString());
    }
    return builder.build();
  }

  private RetrievalResult toResult(final RetrievedChunk chunk) {
    final RetrievalResult.Builder result =
        RetrievalResult.newBuilder()
            .setChunkId(chunk.chunkId().toString())
            .setDocumentVersionId(chunk.documentVersionId().toString())
            .setOrdinal(chunk.ordinal())
            .setSectionPath(chunk.sectionPath())
            .setText(chunk.text())
            .setTokenCount(chunk.tokenCount())
            .setSourceRange(
                SourceRange.newBuilder()
                    .setStart(chunk.sourceCharStart())
                    .setEnd(chunk.sourceCharEnd()))
            .setScore(chunk.score())
            .setRetrievalMethod(chunk.retrievalMethod())
            .setDistanceMetric(chunk.distanceMetric())
            .setSource(
                DocumentMetadata.newBuilder()
                    .setDocType(orUnknown(chunk.docType()))
                    .setPublisher(orUnknown(chunk.publisher()))
                    .setTitle(orUnknown(chunk.sourceTitle()))
                    .setVersion(orUnknown(chunk.version()))
                    .setEffectiveDate(
                        chunk.effectiveDate() == null
                            ? "Unknown"
                            : chunk.effectiveDate().toString()))
            .putAllMetadata(chunk.metadata())
            .setDocumentStatus(chunk.documentStatus())
            .setStale(chunk.stale());
    if (chunk.vectorRank() != null) {
      result.setVectorRank(chunk.vectorRank());
    }
    if (chunk.lexicalRank() != null) {
      result.setLexicalRank(chunk.lexicalRank());
    }
    if (chunk.vectorScore() != null) {
      result.setVectorScore(chunk.vectorScore());
    }
    if (chunk.lexicalScore() != null) {
      result.setLexicalScore(chunk.lexicalScore());
    }
    if (chunk.fusedScore() != null) {
      result.setFusedScore(chunk.fusedScore());
    }
    return result.build();
  }

  private String orUnknown(final String value) {
    return value == null || value.isBlank() ? "Unknown" : value;
  }
}
