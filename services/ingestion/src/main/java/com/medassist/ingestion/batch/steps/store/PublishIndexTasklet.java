package com.medassist.ingestion.batch.steps.store;

import com.medassist.ingestion.batch.stage.DurableStageItem;
import com.medassist.ingestion.batch.stage.DurableStageRepository;
import com.medassist.ingestion.batch.stage.IngestionStageStatus;
import com.medassist.ingestion.batch.stage.QuarantineStage;
import com.medassist.ingestion.batch.steps.IngestionStepContext;
import com.medassist.ingestion.pipeline.store.IndexingPersistencePort;
import com.medassist.ingestion.pipeline.store.IndexingPersistenceRequest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/** Publishes index-ready rows and advances the durable stage only after persistence succeeds. */
public final class PublishIndexTasklet implements Tasklet {
  private final DurableStageRepository stageRepository;
  private final IndexingPersistenceRequestFactory requestFactory;
  private final IndexingPersistencePort persistencePort;

  public PublishIndexTasklet(
      final DurableStageRepository stageRepository,
      final IndexingPersistenceRequestFactory requestFactory,
      final IndexingPersistencePort persistencePort) {
    this.stageRepository = Objects.requireNonNull(stageRepository, "stageRepository");
    this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory");
    this.persistencePort = Objects.requireNonNull(persistencePort, "persistencePort");
  }

  @Override
  public RepeatStatus execute(
      final StepContribution contribution, final ChunkContext chunkContext) {
    final UUID runId = IngestionStepContext.runId(chunkContext);
    final List<DurableStageItem> items =
        stageRepository.findByRunAndState(runId, IngestionStageStatus.INDEX_READY);
    if (items == null) {
      throw new IllegalStateException("durable stage returned no item list");
    }
    for (final DurableStageItem item : items) {
      if (item == null) {
        throw new IllegalStateException("durable stage returned an invalid item");
      }
      contribution.incrementReadCount();
      final IndexingPersistenceRequest request;
      try {
        request = requestFactory.create(item);
        if (request == null) {
          throw new IndexingPersistenceRequestFactoryException(
              IndexingPersistenceRequestFactoryException.Failure.INVALID_REQUEST);
        }
      } catch (final IndexingPersistenceRequestFactoryException exception) {
        stageRepository.quarantine(
            runId,
            item.documentVersionId(),
            IngestionStageStatus.INDEX_READY,
            QuarantineStage.INDEXING,
            exception.failure().errorCode(),
            exception.failure().safeReason());
        contribution.incrementWriteCount(1);
        contribution.incrementProcessSkipCount();
        continue;
      }
      persistencePort.persist(request);
      stageRepository.markIndexed(
          runId, item.documentVersionId(), IngestionStageStatus.INDEX_READY);
      contribution.incrementWriteCount(1);
    }
    return RepeatStatus.FINISHED;
  }
}
