package com.medassist.ingestion.context.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medassist.domain.Chunk;
import com.medassist.domain.SourceRange;
import com.medassist.ingestion.context.ApprovedCostGate;
import com.medassist.ingestion.context.ContextDocument;
import com.medassist.ingestion.context.ContextLlmResponse;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.context.ContextualRetrievalService;
import com.medassist.ingestion.context.InMemoryContextCache;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingPort;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingResponse;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import com.medassist.ingestion.pipeline.index.EmbeddingVector;
import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import com.medassist.ingestion.pipeline.scan.PhiDetectionResponse;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScanner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;

class ContextBackfillTaskletTest {
  private static final EmbeddingModel MODEL = new EmbeddingModel("test-model", "v1", 2);

  @Test
  void rerunUsesCachedLlmContextWhenFinalWriteWasNotObserved() throws Exception {
    final ContextBackfillRepository repository = mock(ContextBackfillRepository.class);
    final ContextBackfillDocument document = document();
    when(repository.findPending(
            eq(ContextualRetrievalMode.LLM_GENERATED), eq("prompt-v1"), anyInt()))
        .thenReturn(List.of(document));
    final AtomicInteger llmCalls = new AtomicInteger();
    final ApprovedCostGate costGate = mock(ApprovedCostGate.class);
    final ContextualRetrievalService contextualService =
        new ContextualRetrievalService(
            new InMemoryContextCache(),
            request -> {
              llmCalls.incrementAndGet();
              return new ContextLlmResponse("Generated context");
            },
            costGate);
    final AtomicInteger embeddingCalls = new AtomicInteger();
    final BatchEmbeddingPort embeddingPort =
        request -> {
          embeddingCalls.incrementAndGet();
          return new BatchEmbeddingResponse(
              MODEL.name(),
              MODEL.version(),
              MODEL.dimension(),
              List.of(new EmbeddingVector(List.of(0.1F, 0.2F))));
        };
    final PostDeidentificationPhiScanner scanner =
        new PostDeidentificationPhiScanner(request -> new PhiDetectionResponse(Set.of()));
    final ContextBackfillTasklet tasklet =
        tasklet(
            repository,
            contextualService,
            embeddingPort,
            scanner,
            ContextualRetrievalMode.LLM_GENERATED);

    tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));
    tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

    assertThat(llmCalls).hasValue(1);
    assertThat(embeddingCalls).hasValue(2);
    verify(repository, times(2)).save(any(ContextBackfillWrite.class));
  }

  @Test
  void suspectContextIsQueuedWithoutEmbeddingOrPublication() throws Exception {
    final ContextBackfillRepository repository = mock(ContextBackfillRepository.class);
    when(repository.findPending(eq(ContextualRetrievalMode.RULE_BASED), anyString(), anyInt()))
        .thenReturn(List.of(document()));
    final ApprovedCostGate costGate = mock(ApprovedCostGate.class);
    final ContextualRetrievalService contextualService =
        new ContextualRetrievalService(
            new InMemoryContextCache(), request -> new ContextLlmResponse("unused"), costGate);
    final BatchEmbeddingPort embeddingPort = mock(BatchEmbeddingPort.class);
    final PostDeidentificationPhiScanner scanner =
        new PostDeidentificationPhiScanner(request -> new PhiDetectionResponse(Set.of("PERSON")));
    final ContextBackfillTasklet tasklet =
        tasklet(
            repository,
            contextualService,
            embeddingPort,
            scanner,
            ContextualRetrievalMode.RULE_BASED);

    tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class));

    verify(repository)
        .enqueuePhiReview(
            document().chunks().getFirst().id(), PhiScanStatus.SUSPECT, Set.of("PERSON"));
    verify(embeddingPort, never()).embed(any());
    verify(repository, never()).save(any());
  }

  private static ContextBackfillTasklet tasklet(
      final ContextBackfillRepository repository,
      final ContextualRetrievalService contextualService,
      final BatchEmbeddingPort embeddingPort,
      final PostDeidentificationPhiScanner scanner,
      final ContextualRetrievalMode mode) {
    return new ContextBackfillTasklet(
        repository,
        contextualService,
        embeddingPort,
        scanner,
        MODEL,
        mode,
        "prompt-v1",
        Duration.ofSeconds(1),
        10);
  }

  private static ContextBackfillDocument document() {
    final UUID versionId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    final Chunk chunk =
        new Chunk(
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            versionId,
            0,
            "1",
            "Deidentified source text",
            3,
            new SourceRange(0, 24),
            Map.of("breadcrumb", "Dose", "chunking_strategy_id", "structure-v1"));
    return new ContextBackfillDocument(
        new ContextDocument(versionId, "Guideline", "Publisher", "Shared summary"), List.of(chunk));
  }
}
