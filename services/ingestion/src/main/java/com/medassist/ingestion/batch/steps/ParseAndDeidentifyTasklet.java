package com.medassist.ingestion.batch.steps;

import com.medassist.ingestion.batch.stage.DurableStageItem;
import com.medassist.ingestion.batch.stage.DurableStageRepository;
import com.medassist.ingestion.batch.stage.IngestionStageStatus;
import com.medassist.ingestion.batch.stage.QuarantineStage;
import com.medassist.ingestion.discovery.DiscoveryException;
import com.medassist.ingestion.discovery.ObjectDescriptor;
import com.medassist.ingestion.discovery.ObjectDiscoveryResult;
import com.medassist.ingestion.discovery.ObjectStoreCatalog;
import com.medassist.ingestion.pipeline.model.FailureStage;
import com.medassist.ingestion.pipeline.model.IngestionWorkItem;
import com.medassist.ingestion.pipeline.model.ParseAndDeidentifyState;
import com.medassist.ingestion.pipeline.parse.ParseAndDeidentifyProcessor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/** Restores discovered items, then persists only de-identified parser output. */
public final class ParseAndDeidentifyTasklet implements Tasklet {
  private final ObjectStoreCatalog objectStoreCatalog;
  private final DurableStageRepository stageRepository;
  private final ParseAndDeidentifyProcessor processor;

  public ParseAndDeidentifyTasklet(
      final ObjectStoreCatalog objectStoreCatalog,
      final DurableStageRepository stageRepository,
      final ParseAndDeidentifyProcessor processor) {
    this.objectStoreCatalog = Objects.requireNonNull(objectStoreCatalog, "objectStoreCatalog");
    this.stageRepository = Objects.requireNonNull(stageRepository, "stageRepository");
    this.processor = Objects.requireNonNull(processor, "processor");
  }

  @Override
  public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext)
      throws Exception {
    final UUID runId = IngestionStepContext.runId(chunkContext);
    final List<DurableStageItem> items =
        stageRepository.findByRunAndState(runId, IngestionStageStatus.DISCOVERED);
    for (final DurableStageItem item : items) {
      contribution.incrementReadCount();
      final ObjectDescriptor descriptor = findDescriptor(item);
      if (descriptor == null) {
        quarantineMissingObject(runId, item);
        contribution.incrementWriteCount(1);
        contribution.incrementProcessSkipCount();
        continue;
      }
      final ParseAndDeidentifyState outcome =
          processor.process(
              new IngestionWorkItem(
                  toDiscoveryResult(item, descriptor),
                  item.logicalDocumentId(),
                  item.documentVersionId()));
      if (outcome.isQuarantined()) {
        stageRepository.quarantine(
            runId,
            item.documentVersionId(),
            IngestionStageStatus.DISCOVERED,
            quarantineStage(outcome.failureStage()),
            errorCode(outcome.failureStage()),
            outcome.failureReason());
        contribution.incrementProcessSkipCount();
      } else {
        stageRepository.saveDeidentified(
            runId,
            item.documentVersionId(),
            IngestionStageStatus.DISCOVERED,
            outcome.deidentifiedDocument(),
            outcome.phiTypeCounts(),
            outcome.policyVersion(),
            outcome.status());
      }
      contribution.incrementWriteCount(1);
    }
    return RepeatStatus.FINISHED;
  }

  private ObjectDescriptor findDescriptor(final DurableStageItem item) throws DiscoveryException {
    final List<ObjectDescriptor> descriptors = objectStoreCatalog.listObjects();
    if (descriptors == null) {
      throw new IllegalStateException("object catalog returned no descriptor list");
    }
    ObjectDescriptor match = null;
    for (final ObjectDescriptor descriptor : descriptors) {
      if (descriptor == null) {
        throw new IllegalStateException("object catalog returned an invalid descriptor");
      }
      if (item.sourceUri().equals(descriptor.storageUri())) {
        if (match != null) {
          return null;
        }
        match = descriptor;
      }
    }
    return match;
  }

  private static ObjectDiscoveryResult toDiscoveryResult(
      final DurableStageItem item, final ObjectDescriptor descriptor) {
    return new ObjectDiscoveryResult(
        descriptor,
        item.contentHash(),
        Optional.ofNullable(item.previousContentHash()),
        item.classification(),
        item.forceReprocess());
  }

  private void quarantineMissingObject(final UUID runId, final DurableStageItem item) {
    stageRepository.quarantine(
        runId,
        item.documentVersionId(),
        IngestionStageStatus.DISCOVERED,
        QuarantineStage.PARSE,
        "OBJECT_UNAVAILABLE",
        "object descriptor unavailable during restart");
  }

  private static QuarantineStage quarantineStage(final FailureStage failureStage) {
    return failureStage == FailureStage.DEIDENTIFICATION
        ? QuarantineStage.DEIDENTIFICATION
        : QuarantineStage.PARSE;
  }

  private static String errorCode(final FailureStage failureStage) {
    return failureStage == FailureStage.DEIDENTIFICATION
        ? "DEIDENTIFICATION_FAILED"
        : "PARSER_FAILED";
  }
}
