package com.medassist.ingestion.batch.steps.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.medassist.domain.DocumentIR;
import com.medassist.domain.Section;
import com.medassist.ingestion.batch.stage.DurableStageItem;
import com.medassist.ingestion.batch.stage.DurableStageRepository;
import com.medassist.ingestion.batch.stage.IngestionStageStatus;
import com.medassist.ingestion.batch.stage.QuarantineStage;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.discovery.DiscoveryClassification;
import com.medassist.ingestion.pipeline.index.DocumentIndexingProcessor;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import com.medassist.ingestion.pipeline.index.IndexingException;
import com.medassist.ingestion.pipeline.index.IndexingRequest;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

class IndexPreparationTaskletTest {
  private static final UUID RUN_ID = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();
  private static final UUID VERSION_ID = UUID.randomUUID();
  private static final EmbeddingModel MODEL = new EmbeddingModel("bge-m3", "m1-baseline", 1024);

  @Test
  void rebuildsSafeRequestAndPersistsIndexReady() throws Exception {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final DocumentIndexingProcessor processor = mock(DocumentIndexingProcessor.class);
    final IndexingResult result = mock(IndexingResult.class);
    final DurableStageItem item = safeItem();
    when(repository.findByRunAndState(RUN_ID, IngestionStageStatus.DEIDENTIFIED))
        .thenReturn(List.of(item));
    when(processor.process(any(IndexingRequest.class))).thenReturn(result);

    final IndexPreparationTasklet tasklet =
        new IndexPreparationTasklet(repository, processor, configuration());
    tasklet.execute(new StepContribution(stepExecution()), contextWithRunId());

    final ArgumentCaptor<IndexingRequest> request = ArgumentCaptor.forClass(IndexingRequest.class);
    verify(processor).process(request.capture());
    assertThat(request.getValue().state().deidentifiedDocument()).isSameAs(item.deidentifiedIr());
    assertThat(request.getValue().state().documentVersionId()).isEqualTo(VERSION_ID);
    assertThat(request.getValue().chunkingStrategyId()).isEqualTo("structure-v1");
    assertThat(request.getValue().contextualRetrievalMode())
        .isEqualTo(ContextualRetrievalMode.RULE_BASED);
    assertThat(request.getValue().embeddingModel()).isEqualTo(MODEL);
    assertThat(request.getValue().contextDocument().title()).isEqualTo("Safe title");
    verify(repository)
        .saveIndexingResult(RUN_ID, VERSION_ID, IngestionStageStatus.DEIDENTIFIED, result);
    verify(repository, never()).quarantine(any(), any(), any(), any(), any(), any());
  }

  @Test
  void quarantinesBusinessFailureWithoutEchoingFailureDetails() throws Exception {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final DocumentIndexingProcessor processor = mock(DocumentIndexingProcessor.class);
    final DurableStageItem item = safeItem();
    when(repository.findByRunAndState(RUN_ID, IngestionStageStatus.DEIDENTIFIED))
        .thenReturn(List.of(item));
    when(processor.process(any(IndexingRequest.class)))
        .thenThrow(new IndexingException("contains sensitive implementation detail"));

    new IndexPreparationTasklet(repository, processor, configuration())
        .execute(new StepContribution(stepExecution()), contextWithRunId());

    verify(repository)
        .quarantine(
            RUN_ID,
            VERSION_ID,
            IngestionStageStatus.DEIDENTIFIED,
            QuarantineStage.INDEXING,
            "INDEX_PREPARATION_FAILED",
            "index preparation failed");
    verify(repository, never()).saveIndexingResult(any(), any(), any(), any());
  }

  @Test
  void propagatesInfrastructureFailureAndDoesNotQuarantine() throws Exception {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final DocumentIndexingProcessor processor = mock(DocumentIndexingProcessor.class);
    final DurableStageItem item = safeItem();
    final RuntimeException failure = new RuntimeException("driver detail");
    when(repository.findByRunAndState(RUN_ID, IngestionStageStatus.DEIDENTIFIED))
        .thenReturn(List.of(item));
    when(processor.process(any(IndexingRequest.class))).thenThrow(failure);

    assertThatThrownBy(
            () ->
                new IndexPreparationTasklet(repository, processor, configuration())
                    .execute(new StepContribution(stepExecution()), contextWithRunId()))
        .isSameAs(failure);
    verify(repository, never()).quarantine(any(), any(), any(), any(), any(), any());
  }

  @Test
  void missingRunIdFailsBeforeReadingStage() {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final DocumentIndexingProcessor processor = mock(DocumentIndexingProcessor.class);

    assertThatThrownBy(
            () ->
                new IndexPreparationTasklet(repository, processor, configuration())
                    .execute(new StepContribution(stepExecution()), contextWithoutRunId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ingestion run id is missing or invalid");
    verifyNoInteractions(repository, processor);
  }

  private static IndexPreparationConfiguration configuration() {
    return new IndexPreparationConfiguration(
        "structure-v1",
        new com.medassist.ingestion.chunking.ChunkingOptions(512, 1024, 100, 50),
        ContextualRetrievalMode.RULE_BASED,
        "context-v1",
        MODEL);
  }

  private static DurableStageItem safeItem() {
    return new DurableStageItem(
        RUN_ID,
        DOCUMENT_ID,
        VERSION_ID,
        URI.create("s3://safe/source"),
        "source-id",
        "application/pdf",
        42,
        "sha256-current",
        null,
        DiscoveryClassification.NEW,
        Map.of("etag", "safe-etag"),
        false,
        IngestionStageStatus.DEIDENTIFIED,
        new DocumentIR(
            List.of(new Section("1", "Overview", 1, "Safe summary text", List.of())),
            List.of(),
            Map.of("title", "Safe title", "publisher", "Safe publisher")),
        Map.of("PERSON", 1),
        "policy-v1",
        ProcessingStatus.SUCCEEDED,
        null,
        null,
        null,
        null);
  }

  private static StepExecution stepExecution() {
    final JobExecution jobExecution = mock(JobExecution.class);
    when(jobExecution.getExecutionContext()).thenReturn(new ExecutionContext());
    final StepExecution stepExecution = mock(StepExecution.class);
    when(stepExecution.getJobExecution()).thenReturn(jobExecution);
    return stepExecution;
  }

  private static ChunkContext contextWithRunId() {
    final StepExecution execution = stepExecution();
    execution
        .getJobExecution()
        .getExecutionContext()
        .putString(IndexPreparationTasklet.RUN_ID_CONTEXT_KEY, RUN_ID.toString());
    return new ChunkContext(new StepContext(execution));
  }

  private static ChunkContext contextWithoutRunId() {
    return new ChunkContext(new StepContext(stepExecution()));
  }
}
