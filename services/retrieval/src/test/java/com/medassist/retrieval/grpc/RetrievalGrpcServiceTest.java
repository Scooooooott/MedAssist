package com.medassist.retrieval.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.common.resilience.Degradation;
import com.medassist.common.resilience.FallbackMode;
import com.medassist.contracts.v1.SearchResponse;
import com.medassist.retrieval.application.RetrievalService;
import com.medassist.retrieval.application.model.ContextualRetrievalMode;
import com.medassist.retrieval.application.model.RetrievalFilters;
import com.medassist.retrieval.application.model.RetrievalMode;
import com.medassist.retrieval.application.model.SearchOutcome;
import com.medassist.retrieval.application.model.SearchQuery;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RetrievalGrpcServiceTest {
  @Test
  void mapsStructuredAndLegacyDegradationFields() {
    final RetrievalService retrievalService = mock(RetrievalService.class);
    final Degradation degradation =
        new Degradation(
            "LEXICAL_CHANNEL_FAILED",
            "LEXICAL_RETRIEVAL",
            FallbackMode.VECTOR_RESULTS,
            "lexical retrieval unavailable; vector results retained");
    when(retrievalService.search(any()))
        .thenReturn(
            new SearchOutcome(
                query(),
                List.of(),
                2,
                3,
                true,
                List.of("LEXICAL_CHANNEL_FAILED"),
                List.of(degradation)));
    @SuppressWarnings("unchecked")
    final StreamObserver<SearchResponse> observer = mock(StreamObserver.class);

    new RetrievalGrpcService(retrievalService)
        .search(
            com.medassist.contracts.v1.SearchRequest.newBuilder()
                .setQuery("aspirin")
                .setRole("CLINICIAN")
                .build(),
            observer);

    final ArgumentCaptor<SearchResponse> response = ArgumentCaptor.forClass(SearchResponse.class);
    verify(observer).onNext(response.capture());
    verify(observer).onCompleted();
    assertThat(response.getValue().getDegradationReasonsList())
        .containsExactly("LEXICAL_CHANNEL_FAILED");
    assertThat(response.getValue().getDegradationsCount()).isEqualTo(1);
    assertThat(response.getValue().getDegradations(0).getCode())
        .isEqualTo("LEXICAL_CHANNEL_FAILED");
    assertThat(response.getValue().getDegradations(0).getAffectedStage())
        .isEqualTo("LEXICAL_RETRIEVAL");
    assertThat(response.getValue().getDegradations(0).getFallbackMode())
        .isEqualTo("VECTOR_RESULTS");
  }

  private SearchQuery query() {
    return new SearchQuery(
        "aspirin",
        5,
        50,
        new RetrievalFilters(null, null, null, null, null),
        "CLINICIAN",
        "bge-m3",
        "v1",
        "COSINE",
        RetrievalMode.HYBRID,
        false,
        false,
        ContextualRetrievalMode.OFF,
        "structure-v1",
        3);
  }
}
