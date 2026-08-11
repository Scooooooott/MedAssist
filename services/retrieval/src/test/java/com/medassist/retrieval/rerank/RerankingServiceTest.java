package com.medassist.retrieval.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import com.medassist.retrieval.application.model.RetrievedChunk;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RerankingServiceTest {
  private static final Duration TIMEOUT = Duration.ofMillis(250);
  private static final String MODEL = "cross-encoder/ms-marco-MiniLM-L-6-v2";
  private static final String CONTEXT_ONLY_MARKER = "CTX_ONLY_M2_3_BOUNDARY_MARKER";
  private final RetrievedChunk first = chunk("00000000-0000-0000-0000-000000000001", "first");
  private final RetrievedChunk second = chunk("00000000-0000-0000-0000-000000000002", "second");
  private final RetrievedChunk third = chunk("00000000-0000-0000-0000-000000000003", "third");

  @Test
  void disabledReturnsOriginalTopKWithoutCallingBackend() {
    final AtomicReference<Boolean> called = new AtomicReference<>(false);
    final RerankingService service =
        new RerankingService(
            (query, candidates, modelName, timeout) -> {
              called.set(true);
              return null;
            });

    final RerankingResult result =
        service.rerank("query", List.of(first, second, third), 2, false, MODEL, TIMEOUT);

    assertThat(result.chunks()).containsExactly(first, second);
    assertThat(result.degraded()).isFalse();
    assertThat(result.reason()).isEqualTo("DISABLED");
    assertThat(called).hasValue(false);
  }

  @Test
  void successfulResponseOrdersByRankAndRetainsSourceMetadata() {
    final RerankingService service =
        new RerankingService(
            (query, candidates, modelName, timeout) ->
                new RerankClientResponse(
                    List.of(
                        new RerankScore(third.chunkId().toString(), 0.3, 3),
                        new RerankScore(first.chunkId().toString(), 0.9, 1),
                        new RerankScore(second.chunkId().toString(), 0.8, 2)),
                    MODEL,
                    "rerank-v1"));

    final RerankingResult result =
        service.rerank("query", List.of(first, second, third), 2, true, MODEL, TIMEOUT);

    assertThat(result.chunks()).containsExactly(first, second);
    assertThat(result.chunks().get(0)).isSameAs(first);
    assertThat(result.chunks().get(0).metadata()).containsEntry("source", "test");
    assertThat(result.degraded()).isFalse();
    assertThat(result.reason()).isEqualTo("OK");
    assertThat(result.modelName()).isEqualTo(MODEL);
    assertThat(result.modelVersion()).isEqualTo("rerank-v1");
  }

  @Test
  void sendsAllFusedCandidatesAndSelectedModelAndTimeoutToPort() {
    final AtomicReference<List<RetrievedChunk>> receivedCandidates = new AtomicReference<>();
    final AtomicReference<String> receivedModel = new AtomicReference<>();
    final AtomicReference<Duration> receivedTimeout = new AtomicReference<>();
    final RerankingService service =
        new RerankingService(
            (query, candidates, modelName, timeout) -> {
              receivedCandidates.set(candidates);
              receivedModel.set(modelName);
              receivedTimeout.set(timeout);
              return new RerankClientResponse(
                  List.of(
                      new RerankScore(first.chunkId().toString(), 0.9, 1),
                      new RerankScore(second.chunkId().toString(), 0.8, 2),
                      new RerankScore(third.chunkId().toString(), 0.7, 3)),
                  MODEL,
                  "v1");
            });

    service.rerank("query", List.of(first, second, third), 1, true, MODEL, TIMEOUT);

    assertThat(receivedCandidates).hasValue(List.of(first, second, third));
    assertThat(receivedModel).hasValue(MODEL);
    assertThat(receivedTimeout).hasValue(TIMEOUT);
  }

  @Test
  void rerankerReceivesOriginalTextWithoutContextPrefixMarker() {
    final RetrievedChunk contextualChunk =
        chunk(
            "00000000-0000-0000-0000-000000000004",
            "The original rerank text.",
            CONTEXT_ONLY_MARKER);
    final AtomicReference<List<RetrievedChunk>> receivedCandidates = new AtomicReference<>();
    final RerankingService service =
        new RerankingService(
            (query, candidates, modelName, timeout) -> {
              receivedCandidates.set(candidates);
              return new RerankClientResponse(
                  List.of(new RerankScore(contextualChunk.chunkId().toString(), 0.9, 1)),
                  MODEL,
                  "v1");
            });

    service.rerank("query", List.of(contextualChunk), 1, true, MODEL, TIMEOUT);

    assertThat(receivedCandidates).hasValue(List.of(contextualChunk));
    assertThat(receivedCandidates.get().get(0).text())
        .isEqualTo("The original rerank text.")
        .doesNotContain(CONTEXT_ONLY_MARKER);
  }

  @Test
  void timeoutAndBackendFailureReturnOriginalTopKWithReason() {
    final RerankingService timeoutService =
        new RerankingService(
            (query, candidates, modelName, timeout) -> {
              throw RerankClientException.timeout("timed out", null);
            });
    final RerankingService backendService =
        new RerankingService(
            (query, candidates, modelName, timeout) -> {
              throw RerankClientException.backend("unavailable", null);
            });

    final RerankingResult timeout =
        timeoutService.rerank("query", List.of(first, second), 1, true, MODEL, TIMEOUT);
    final RerankingResult backend =
        backendService.rerank("query", List.of(first, second), 1, true, MODEL, TIMEOUT);

    assertThat(timeout.chunks()).containsExactly(first);
    assertThat(timeout.degraded()).isTrue();
    assertThat(timeout.reason()).isEqualTo("TIMEOUT");
    assertThat(backend.chunks()).containsExactly(first);
    assertThat(backend.degraded()).isTrue();
    assertThat(backend.reason()).isEqualTo("BACKEND_ERROR");
  }

  @Test
  void malformedResponsesAreRejectedAndDegraded() {
    final RerankingService missing =
        serviceWithResults(List.of(new RerankScore(first.chunkId().toString(), 0.9, 1)));
    final RerankingService duplicate =
        serviceWithResults(
            List.of(
                new RerankScore(first.chunkId().toString(), 0.9, 1),
                new RerankScore(first.chunkId().toString(), 0.8, 2),
                new RerankScore(second.chunkId().toString(), 0.7, 3)));
    final RerankingService unknown =
        serviceWithResults(
            List.of(
                new RerankScore(first.chunkId().toString(), 0.9, 1),
                new RerankScore(second.chunkId().toString(), 0.8, 2),
                new RerankScore(UUID.randomUUID().toString(), 0.7, 3)));

    assertThat(
            missing
                .rerank("query", List.of(first, second, third), 2, true, MODEL, TIMEOUT)
                .reason())
        .isEqualTo("MALFORMED_RESPONSE");
    assertThat(
            duplicate
                .rerank("query", List.of(first, second, third), 2, true, MODEL, TIMEOUT)
                .reason())
        .isEqualTo("MALFORMED_RESPONSE");
    assertThat(
            unknown
                .rerank("query", List.of(first, second, third), 2, true, MODEL, TIMEOUT)
                .reason())
        .isEqualTo("MALFORMED_RESPONSE");
  }

  @Test
  void modelIdentityMismatchIsDegraded() {
    final RerankingService service =
        new RerankingService(
            (query, candidates, modelName, timeout) ->
                new RerankClientResponse(
                    List.of(
                        new RerankScore(first.chunkId().toString(), 0.9, 1),
                        new RerankScore(second.chunkId().toString(), 0.8, 2),
                        new RerankScore(third.chunkId().toString(), 0.7, 3)),
                    "unexpected-model",
                    "v1"));

    final RerankingResult result =
        service.rerank("query", List.of(first, second, third), 2, true, MODEL, TIMEOUT);

    assertThat(result.degraded()).isTrue();
    assertThat(result.reason()).isEqualTo("MALFORMED_RESPONSE");
    assertThat(result.chunks()).containsExactly(first, second);
  }

  @Test
  void duplicateInputCandidateIdsAreRejectedBeforeBackendCall() {
    final AtomicReference<Boolean> called = new AtomicReference<>(false);
    final RerankingService service =
        new RerankingService(
            (query, candidates, modelName, timeout) -> {
              called.set(true);
              return null;
            });

    final RerankingResult result =
        service.rerank("query", List.of(first, first), 1, true, MODEL, TIMEOUT);

    assertThat(result.chunks()).containsExactly(first);
    assertThat(result.degraded()).isTrue();
    assertThat(result.reason()).isEqualTo("MALFORMED_CANDIDATES");
    assertThat(called).hasValue(false);
  }

  private RerankingService serviceWithResults(final List<RerankScore> results) {
    return new RerankingService(
        (query, candidates, modelName, timeout) -> new RerankClientResponse(results, MODEL, "v1"));
  }

  private static RetrievedChunk chunk(final String id, final String text) {
    return chunk(id, text, null);
  }

  private static RetrievedChunk chunk(
      final String id, final String text, final String contextPrefixMarker) {
    return new RetrievedChunk(
        UUID.fromString(id),
        UUID.randomUUID(),
        1,
        "section",
        text,
        2,
        0,
        text.length(),
        0.5,
        "HYBRID",
        "COSINE",
        "GUIDELINE",
        "publisher",
        "title",
        "v1",
        LocalDate.of(2026, 1, 1),
        contextPrefixMarker == null
            ? Map.of("source", "test")
            : Map.of("source", "test", "context_prefix", contextPrefixMarker));
  }
}
