package com.medassist.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.retrieval.api.dto.AnswerRequest;
import com.medassist.retrieval.api.dto.AnswerResponse;
import com.medassist.retrieval.application.generation.AnswerGenerator;
import com.medassist.retrieval.application.generation.GeneratedAnswer;
import com.medassist.retrieval.application.generation.GeneratedCitation;
import com.medassist.retrieval.application.generation.GenerationEvent;
import com.medassist.retrieval.application.generation.TokenUsage;
import com.medassist.retrieval.application.model.ContextualRetrievalMode;
import com.medassist.retrieval.application.model.RetrievalFilters;
import com.medassist.retrieval.application.model.RetrievalMode;
import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchOutcome;
import com.medassist.retrieval.application.model.SearchQuery;
import com.medassist.retrieval.cache.AnswerResponseCache;
import com.medassist.retrieval.config.RetrievalProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AnswerServiceTest {
  private static final UUID CHUNK_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private RetrievalService retrievalService;
  private AnswerGenerator generator;
  private AnswerService service;

  @BeforeEach
  void setUp() {
    retrievalService = mock(RetrievalService.class);
    generator = mock(AnswerGenerator.class);
    final AnswerResponseCache cache = mock(AnswerResponseCache.class);
    when(cache.getOrCompute(any(), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              final Supplier<AnswerResponse> supplier = invocation.getArgument(1);
              return supplier.get();
            });
    final RetrievalProperties properties = new RetrievalProperties();
    properties.setAbstainMessage("Configured safe abstention.");
    service =
        new AnswerService(
            retrievalService,
            new CitationValidator(),
            new RetrievalResponseMapper(),
            cache,
            generator,
            properties);
  }

  @Test
  void validGeneratedCitationProducesGroundedAnswer() {
    when(retrievalService.search(any(com.medassist.retrieval.api.dto.SearchRequest.class)))
        .thenReturn(outcome(List.of(chunk())));
    when(generator.generate("question", List.of(chunk()))).thenReturn(generated(CHUNK_ID));

    final AnswerResponse response = service.answer(request());

    assertThat(response.answer()).isEqualTo("Grounded answer.");
    assertThat(response.sufficientEvidence()).isTrue();
    assertThat(response.citations()).hasSize(1);
  }

  @Test
  void invalidGeneratedCitationForcesConfiguredAbstention() {
    when(retrievalService.search(any(com.medassist.retrieval.api.dto.SearchRequest.class)))
        .thenReturn(outcome(List.of(chunk())));
    when(generator.generate("question", List.of(chunk())))
        .thenReturn(generated(UUID.fromString("00000000-0000-0000-0000-000000000099")));

    final AnswerResponse response = service.answer(request());

    assertThat(response.abstained()).isTrue();
    assertThat(response.answer()).isEqualTo("Configured safe abstention.");
    assertThat(response.citations()).isEmpty();
  }

  @Test
  void emptyRetrievalAbstainsWithoutCallingModel() {
    when(retrievalService.search(any(com.medassist.retrieval.api.dto.SearchRequest.class)))
        .thenReturn(outcome(List.of()));

    final AnswerResponse response = service.answer(request());

    assertThat(response.abstained()).isTrue();
    verify(generator, never()).generate(any(), any());
  }

  @Test
  void streamDoesNotReleaseUnsupportedModelTextBeforeCitationValidation() {
    when(retrievalService.search(any(com.medassist.retrieval.api.dto.SearchRequest.class)))
        .thenReturn(outcome(List.of(chunk())));
    final GeneratedAnswer invalid =
        generated(UUID.fromString("00000000-0000-0000-0000-000000000099"));
    when(generator.stream("question", List.of(chunk())))
        .thenReturn(
            Flux.just(
                GenerationEvent.delta("Unsupported answer."), GenerationEvent.complete(invalid)));

    final List<AnswerStreamEvent> events = service.stream(request()).collectList().block();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).finalResponse().abstained()).isTrue();
  }

  @Test
  void streamExpandsTopKOnceWhenInitialRetrievalIsEmpty() {
    when(retrievalService.search(any(com.medassist.retrieval.api.dto.SearchRequest.class)))
        .thenReturn(outcome(List.of()))
        .thenReturn(outcome(List.of(chunk())));
    when(generator.stream("question", List.of(chunk())))
        .thenReturn(Flux.just(GenerationEvent.complete(generated(CHUNK_ID))));

    final List<AnswerStreamEvent> events = service.stream(request()).collectList().block();

    assertThat(events).isNotEmpty();
    assertThat(events.getFirst().isRetry()).isTrue();
    assertThat(events.getFirst().retryStatus().attempt()).isEqualTo(1);
    assertThat(events.getLast().finalResponse().abstained()).isFalse();
    verify(retrievalService, times(2))
        .search(any(com.medassist.retrieval.api.dto.SearchRequest.class));
  }

  @Test
  void validatedStreamEmitsNamedPayloadPartsAndFinalResponse() {
    when(retrievalService.search(any(com.medassist.retrieval.api.dto.SearchRequest.class)))
        .thenReturn(outcome(List.of(chunk())));
    when(generator.stream("question", List.of(chunk())))
        .thenReturn(
            Flux.just(
                GenerationEvent.delta("Grounded "),
                GenerationEvent.delta("answer."),
                GenerationEvent.complete(generated(CHUNK_ID))));

    final List<AnswerStreamEvent> events = service.stream(request()).collectList().block();

    assertThat(events.stream().filter(AnswerStreamEvent::isDelta).map(AnswerStreamEvent::delta))
        .containsExactly("Grounded ", "answer.");
    assertThat(events.get(2).finalResponse().answer()).isEqualTo("Grounded answer.");
  }

  private AnswerRequest request() {
    return new AnswerRequest("question", 5, null, "anonymous", "model", "v1");
  }

  private GeneratedAnswer generated(final UUID chunkId) {
    return new GeneratedAnswer(
        "Grounded answer.",
        List.of(new GeneratedCitation(chunkId, VERSION_ID, "source text", "direct")),
        true,
        TokenUsage.unknown());
  }

  private SearchOutcome outcome(final List<RetrievedChunk> chunks) {
    final SearchQuery query =
        new SearchQuery(
            "question",
            5,
            50,
            new RetrievalFilters(Set.of(), Set.of(), null, null, Set.of()),
            "anonymous",
            "model",
            "v1",
            "COSINE",
            RetrievalMode.VECTOR_ONLY,
            false,
            false,
            ContextualRetrievalMode.OFF,
            "structure-v1",
            3);
    return new SearchOutcome(query, chunks, 2L, 3L);
  }

  private RetrievedChunk chunk() {
    return new RetrievedChunk(
        CHUNK_ID,
        VERSION_ID,
        0,
        "Recommendations",
        "source text",
        2,
        0,
        11,
        0.9,
        "VECTOR",
        "COSINE",
        "GUIDELINE",
        "Publisher",
        "Title",
        "v1",
        LocalDate.of(2025, 1, 1),
        Map.of());
  }
}
