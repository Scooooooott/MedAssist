package com.medassist.retrieval.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medassist.common.context.ContextCarrier;
import com.medassist.common.context.ExecutionContext;
import com.medassist.common.resilience.ComponentPolicyTable;
import com.medassist.common.resilience.DegradationEvent;
import com.medassist.common.resilience.DegradationRecorder;
import com.medassist.common.resilience.FallbackMode;
import com.medassist.common.resilience.ResilienceExecutor;
import com.medassist.retrieval.api.dto.SearchRequest;
import com.medassist.retrieval.application.model.RetrievalMode;
import com.medassist.retrieval.application.model.RetrievedChunk;
import com.medassist.retrieval.application.model.SearchOutcome;
import com.medassist.retrieval.config.RetrievalProperties;
import com.medassist.retrieval.model.ModelVersionMismatchException;
import com.medassist.retrieval.model.QueryEmbedding;
import com.medassist.retrieval.model.QueryEmbeddingClient;
import com.medassist.retrieval.repository.LexicalSearchRepository;
import com.medassist.retrieval.repository.RankedChunk;
import com.medassist.retrieval.repository.VectorSearchRepository;
import com.medassist.retrieval.rerank.RerankClientException;
import com.medassist.retrieval.rerank.RerankClientResponse;
import com.medassist.retrieval.rerank.RerankScore;
import com.medassist.retrieval.rerank.RerankingService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetrievalServiceTest {
  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final List<ResilienceExecutor> resilienceExecutors = new ArrayList<>();

  @BeforeEach
  void bindExecutionContext() {
    ContextCarrier.restore(
        new ExecutionContext("test-user", Set.of("CLINICIAN"), "request-id", "trace-id", Map.of()));
  }

  @AfterEach
  void closeExecutor() {
    executor.shutdownNow();
    resilienceExecutors.forEach(ResilienceExecutor::close);
    ContextCarrier.clear();
  }

  @Test
  void lexicalModeDoesNotCallEmbeddingService() {
    final AtomicInteger embeddingCalls = new AtomicInteger();
    final QueryEmbeddingClient embeddings =
        (query, modelName, modelVersion) -> {
          embeddingCalls.incrementAndGet();
          throw new AssertionError("embedding must not run");
        };
    final RetrievalService service =
        service(embeddings, (query, embedding) -> List.of(), query -> List.of(ranked(chunk(1), 1)));

    final SearchOutcome outcome = service.search(request(RetrievalMode.LEXICAL_ONLY));

    assertEquals(0, embeddingCalls.get());
    assertEquals(1, outcome.chunks().size());
  }

  @Test
  void requestWithoutTopKUsesM22DefaultOfEight() {
    final RetrievalService service =
        service(
            embeddingClient(),
            (query, embedding) -> List.of(),
            query ->
                java.util.stream.IntStream.rangeClosed(1, 10)
                    .mapToObj(index -> ranked(chunk(index), index))
                    .toList());
    final SearchRequest request =
        new SearchRequest(
            "aspirin",
            null,
            null,
            "CLINICIAN",
            "bge-m3",
            "m1-baseline",
            RetrievalMode.LEXICAL_ONLY,
            false,
            false,
            null,
            "structure-v1",
            null);

    final SearchOutcome outcome = service.search(request);

    assertEquals(8, outcome.chunks().size());
  }

  @Test
  void hybridDegradesToVectorWhenLexicalChannelFails() {
    final RetrievedChunk vector = chunk(1);
    final List<DegradationEvent> recorded = new ArrayList<>();
    final RetrievalService service =
        service(
            embeddingClient(),
            (query, embedding) -> List.of(vector),
            query -> {
              throw new IllegalStateException("patient Jane Doe lexical query failed");
            },
            successfulReranking(),
            recorded::add);

    final SearchOutcome outcome = service.search(request(RetrievalMode.HYBRID));

    assertTrue(outcome.degraded());
    assertEquals(List.of("LEXICAL_CHANNEL_FAILED"), outcome.degradationReasons());
    assertEquals("LEXICAL_CHANNEL_FAILED", outcome.degradations().getFirst().code());
    assertEquals("LEXICAL_RETRIEVAL", outcome.degradations().getFirst().affectedStage());
    assertEquals(FallbackMode.VECTOR_RESULTS, outcome.degradations().getFirst().fallbackMode());
    assertEquals(vector.chunkId(), outcome.chunks().get(0).chunkId());
    assertEquals(1, recorded.size());
    assertFalse(recorded.getFirst().toString().contains("Jane Doe"));
    assertFalse(recorded.getFirst().toString().contains(vector.text()));
  }

  @Test
  void rerankFailureRetainsOriginalOrderWithStructuredDegradation() {
    final RetrievedChunk first = chunk(1);
    final RetrievedChunk second = chunk(2);
    final List<DegradationEvent> recorded = new ArrayList<>();
    final RerankingService failedReranker =
        new RerankingService(
            (query, candidates, modelName, timeout) -> {
              throw RerankClientException.backend("patient Jane Doe rerank failed", null);
            },
            newResilienceExecutor());
    final RetrievalService service =
        service(
            embeddingClient(),
            (query, embedding) -> List.of(first, second),
            query -> List.of(),
            failedReranker,
            recorded::add);

    final SearchOutcome outcome = service.search(request(RetrievalMode.VECTOR_ONLY, true));

    assertTrue(outcome.degraded());
    assertEquals(
        List.of(first.chunkId(), second.chunkId()),
        outcome.chunks().stream().map(RetrievedChunk::chunkId).toList());
    assertEquals(List.of("RERANK_BACKEND_ERROR"), outcome.degradationReasons());
    assertEquals("RERANK_BACKEND_ERROR", outcome.degradations().getFirst().code());
    assertEquals(FallbackMode.ORIGINAL_ORDER, outcome.degradations().getFirst().fallbackMode());
    assertEquals(1, recorded.size());
    assertFalse(recorded.getFirst().toString().contains("Jane Doe"));
    assertFalse(recorded.getFirst().toString().contains(first.text()));
  }

  @Test
  void hybridFailsWhenVectorChannelFails() {
    final RetrievalService service =
        service(
            embeddingClient(),
            (query, embedding) -> {
              throw new IllegalStateException("vector unavailable");
            },
            query -> List.of(ranked(chunk(2), 1)));

    assertThrows(IllegalStateException.class, () -> service.search(request(RetrievalMode.HYBRID)));
  }

  @Test
  void modelVersionMismatchFailsInsteadOfMasqueradingAsNoResults() {
    final QueryEmbeddingClient mismatched =
        (query, modelName, modelVersion) -> {
          throw new ModelVersionMismatchException("model identity mismatch");
        };
    final RetrievalService service =
        service(mismatched, (query, embedding) -> List.of(), query -> List.of());

    assertThrows(
        ModelVersionMismatchException.class,
        () -> service.search(request(RetrievalMode.VECTOR_ONLY)));
    assertThrows(
        ModelVersionMismatchException.class, () -> service.search(request(RetrievalMode.HYBRID)));
  }

  @Test
  void hybridFusesBothChannels() {
    final RetrievedChunk shared = chunk(1);
    final RetrievalService service =
        service(
            embeddingClient(),
            (query, embedding) -> List.of(shared),
            query -> List.of(ranked(shared, 1)));

    final SearchOutcome outcome = service.search(request(RetrievalMode.HYBRID));

    assertFalse(outcome.degraded());
    assertEquals("VECTOR+LEXICAL_RRF", outcome.chunks().get(0).retrievalMethod());
  }

  private RetrievalService service(
      final QueryEmbeddingClient embeddings,
      final VectorSearchRepository vector,
      final LexicalSearchRepository lexical) {
    return service(embeddings, vector, lexical, successfulReranking(), DegradationRecorder.noop());
  }

  private RetrievalService service(
      final QueryEmbeddingClient embeddings,
      final VectorSearchRepository vector,
      final LexicalSearchRepository lexical,
      final RerankingService reranking,
      final DegradationRecorder degradationRecorder) {
    final RetrievalProperties properties = new RetrievalProperties();
    properties.setRetrievalTimeout(Duration.ofSeconds(1));
    final ResilienceExecutor resilienceExecutor = newResilienceExecutor();
    return new RetrievalService(
        properties,
        embeddings,
        vector,
        lexical,
        new RrfFusion(),
        reranking,
        executor,
        Optional.of(degradationRecorder),
        resilienceExecutor);
  }

  private RerankingService successfulReranking() {
    return new RerankingService(
        (query, candidates, modelName, timeout) ->
            new RerankClientResponse(
                java.util.stream.IntStream.range(0, candidates.size())
                    .mapToObj(
                        index ->
                            new RerankScore(
                                candidates.get(index).chunkId().toString(),
                                1.0 - index * 0.01,
                                index + 1))
                    .toList(),
                modelName,
                "test-v1"),
        newResilienceExecutor());
  }

  private ResilienceExecutor newResilienceExecutor() {
    final ResilienceExecutor resilienceExecutor =
        new ResilienceExecutor(
            ComponentPolicyTable.conservativeDefaults(),
            Executors.newVirtualThreadPerTaskExecutor());
    resilienceExecutors.add(resilienceExecutor);
    return resilienceExecutor;
  }

  private QueryEmbeddingClient embeddingClient() {
    return (query, modelName, modelVersion) ->
        new QueryEmbedding(modelName, modelVersion, List.of(1.0F), 1L);
  }

  private SearchRequest request(final RetrievalMode mode) {
    return request(mode, false);
  }

  private SearchRequest request(final RetrievalMode mode, final boolean rerankEnabled) {
    return new SearchRequest(
        "aspirin",
        5,
        null,
        "CLINICIAN",
        "bge-m3",
        "m1-baseline",
        mode,
        rerankEnabled,
        false,
        null,
        "structure-v1",
        50);
  }

  private RankedChunk ranked(final RetrievedChunk chunk, final int rank) {
    return new RankedChunk(chunk, rank, chunk.score());
  }

  private RetrievedChunk chunk(final int suffix) {
    return new RetrievedChunk(
        UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix)),
        UUID.fromString("10000000-0000-0000-0000-000000000001"),
        0,
        "1",
        "Evidence text.",
        2,
        0,
        14,
        0.8,
        "TEST",
        "COSINE",
        "GUIDELINE",
        "Publisher",
        "Title",
        "v1",
        LocalDate.of(2026, 1, 1),
        Map.of());
  }
}
