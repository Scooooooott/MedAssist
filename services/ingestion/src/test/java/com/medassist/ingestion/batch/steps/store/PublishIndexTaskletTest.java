package com.medassist.ingestion.batch.steps.store;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.medassist.ingestion.batch.stage.DurableStageItem;
import com.medassist.ingestion.batch.stage.DurableStageRepository;
import com.medassist.ingestion.batch.stage.IngestionStageStatus;
import com.medassist.ingestion.batch.stage.QuarantineStage;
import com.medassist.ingestion.batch.steps.IngestionStepContext;
import com.medassist.ingestion.pipeline.store.IndexingPersistencePort;
import com.medassist.ingestion.pipeline.store.IndexingPersistenceRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

class PublishIndexTaskletTest {
  private static final UUID RUN_ID = UUID.randomUUID();
  private static final UUID VERSION_ID = UUID.randomUUID();

  @Test
  void publishesThenMarksIndexed() throws Exception {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final IndexingPersistenceRequestFactory factory = mock(IndexingPersistenceRequestFactory.class);
    final IndexingPersistencePort persistence = mock(IndexingPersistencePort.class);
    final DurableStageItem item = mock(DurableStageItem.class);
    final IndexingPersistenceRequest request = mock(IndexingPersistenceRequest.class);
    when(repository.findByRunAndState(RUN_ID, IngestionStageStatus.INDEX_READY))
        .thenReturn(List.of(item));
    when(item.documentVersionId()).thenReturn(VERSION_ID);
    when(factory.create(item)).thenReturn(request);

    new PublishIndexTasklet(repository, factory, persistence)
        .execute(new StepContribution(stepExecution()), contextWithRunId());

    verify(persistence).persist(request);
    verify(repository).markIndexed(RUN_ID, VERSION_ID, IngestionStageStatus.INDEX_READY);
    verify(repository, never()).quarantine(any(), any(), any(), any(), any(), any());
  }

  @Test
  void quarantinesFactoryPermanentFailureAndContinues() throws Exception {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final IndexingPersistenceRequestFactory factory = mock(IndexingPersistenceRequestFactory.class);
    final IndexingPersistencePort persistence = mock(IndexingPersistencePort.class);
    final DurableStageItem item = mock(DurableStageItem.class);
    when(repository.findByRunAndState(RUN_ID, IngestionStageStatus.INDEX_READY))
        .thenReturn(List.of(item));
    when(item.documentVersionId()).thenReturn(VERSION_ID);
    when(factory.create(item))
        .thenThrow(
            new IndexingPersistenceRequestFactoryException(
                IndexingPersistenceRequestFactoryException.Failure.INVALID_REQUEST));

    new PublishIndexTasklet(repository, factory, persistence)
        .execute(new StepContribution(stepExecution()), contextWithRunId());

    verify(repository)
        .quarantine(
            RUN_ID,
            VERSION_ID,
            IngestionStageStatus.INDEX_READY,
            QuarantineStage.INDEXING,
            "INDEXING_REQUEST_INVALID",
            "indexing request validation failed");
    verifyNoInteractions(persistence);
    verify(repository, never()).markIndexed(any(), any(), any());
  }

  @Test
  void persistenceFailureIsPropagatedAndDoesNotMarkIndexed() {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final IndexingPersistenceRequestFactory factory = mock(IndexingPersistenceRequestFactory.class);
    final IndexingPersistencePort persistence = mock(IndexingPersistencePort.class);
    final DurableStageItem item = mock(DurableStageItem.class);
    final IndexingPersistenceRequest request = mock(IndexingPersistenceRequest.class);
    final RuntimeException failure =
        new IndexingPersistenceRequestFactoryException(
            IndexingPersistenceRequestFactoryException.Failure.INVALID_REQUEST);
    when(repository.findByRunAndState(RUN_ID, IngestionStageStatus.INDEX_READY))
        .thenReturn(List.of(item));
    when(item.documentVersionId()).thenReturn(VERSION_ID);
    when(factory.create(item)).thenReturn(request);
    when(persistence.persist(request)).thenThrow(failure);

    assertThatThrownBy(
            () ->
                new PublishIndexTasklet(repository, factory, persistence)
                    .execute(new StepContribution(stepExecution()), contextWithRunId()))
        .isSameAs(failure);

    verify(repository, never()).markIndexed(any(), any(), any());
    verify(repository, never()).quarantine(any(), any(), any(), any(), any(), any());
  }

  @Test
  void emptyIndexReadySetIsSuccessfulAndDoesNothing() throws Exception {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final IndexingPersistenceRequestFactory factory = mock(IndexingPersistenceRequestFactory.class);
    final IndexingPersistencePort persistence = mock(IndexingPersistencePort.class);
    when(repository.findByRunAndState(RUN_ID, IngestionStageStatus.INDEX_READY))
        .thenReturn(List.of());

    new PublishIndexTasklet(repository, factory, persistence)
        .execute(new StepContribution(stepExecution()), contextWithRunId());

    verify(repository).findByRunAndState(RUN_ID, IngestionStageStatus.INDEX_READY);
    verifyNoInteractions(factory, persistence);
  }

  @Test
  void missingRunIdFailsBeforeReadingStage() {
    final DurableStageRepository repository = mock(DurableStageRepository.class);
    final IndexingPersistenceRequestFactory factory = mock(IndexingPersistenceRequestFactory.class);
    final IndexingPersistencePort persistence = mock(IndexingPersistencePort.class);

    assertThatThrownBy(
            () ->
                new PublishIndexTasklet(repository, factory, persistence)
                    .execute(new StepContribution(stepExecution()), contextWithoutRunId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ingestion run context is invalid");

    verifyNoInteractions(repository, factory, persistence);
  }

  private static StepExecution stepExecution() {
    return new StepExecution("publish-index", jobExecution());
  }

  private static ChunkContext contextWithRunId() {
    final JobExecution jobExecution = jobExecution();
    final StepExecution execution = new StepExecution("publish-index", jobExecution);
    execution
        .getJobExecution()
        .getExecutionContext()
        .putString(IngestionStepContext.RUN_ID_CONTEXT_KEY, RUN_ID.toString());
    return new ChunkContext(new StepContext(execution));
  }

  private static ChunkContext contextWithoutRunId() {
    return new ChunkContext(new StepContext(stepExecution()));
  }

  private static JobExecution jobExecution() {
    final JobExecution execution = mock(JobExecution.class);
    when(execution.getExecutionContext()).thenReturn(new ExecutionContext());
    return execution;
  }
}
