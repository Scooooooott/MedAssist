package com.medassist.ingestion.batch.steps.index;

import com.medassist.domain.DocumentIR;
import com.medassist.domain.Section;
import com.medassist.ingestion.batch.stage.DurableStageItem;
import com.medassist.ingestion.batch.stage.DurableStageRepository;
import com.medassist.ingestion.batch.stage.IngestionStageStatus;
import com.medassist.ingestion.batch.stage.QuarantineStage;
import com.medassist.ingestion.discovery.ObjectDescriptor;
import com.medassist.ingestion.discovery.ObjectDiscoveryResult;
import com.medassist.ingestion.pipeline.grpc.ModelEmbeddingGrpcClient.ModelEmbeddingPermanentException;
import com.medassist.ingestion.pipeline.index.DocumentIndexingProcessor;
import com.medassist.ingestion.pipeline.index.IndexingException;
import com.medassist.ingestion.pipeline.index.IndexingRequest;
import com.medassist.ingestion.pipeline.model.FailureStage;
import com.medassist.ingestion.pipeline.model.IngestionWorkItem;
import com.medassist.ingestion.pipeline.model.ParseAndDeidentifyState;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/** Prepares safe stage rows for transactional publication by the following batch step. */
public final class IndexPreparationTasklet implements Tasklet {
  public static final String RUN_ID_CONTEXT_KEY = "medassist.ingestion.run-id";
  private static final String INVALID_STAGE_CODE = "INDEX_STAGE_INVALID";
  private static final String INDEXING_FAILED_CODE = "INDEX_PREPARATION_FAILED";
  private static final String INVALID_STAGE_REASON = "persisted index input is invalid";
  private static final String INDEXING_FAILED_REASON = "index preparation failed";

  private final DurableStageRepository stageRepository;
  private final DocumentIndexingProcessor indexingProcessor;
  private final IndexPreparationConfiguration configuration;

  public IndexPreparationTasklet(
      final DurableStageRepository stageRepository,
      final DocumentIndexingProcessor indexingProcessor,
      final IndexPreparationConfiguration configuration) {
    this.stageRepository = java.util.Objects.requireNonNull(stageRepository, "stageRepository");
    this.indexingProcessor =
        java.util.Objects.requireNonNull(indexingProcessor, "indexingProcessor");
    this.configuration = java.util.Objects.requireNonNull(configuration, "configuration");
  }

  @Override
  public RepeatStatus execute(
      final StepContribution contribution, final ChunkContext chunkContext) {
    final UUID runId = runId(chunkContext);
    final List<DurableStageItem> items =
        stageRepository.findByRunAndState(runId, IngestionStageStatus.DEIDENTIFIED);
    for (final DurableStageItem item : items) {
      contribution.incrementReadCount();
      try {
        final IndexingRequest request = requestFor(item);
        final var result = indexingProcessor.process(request);
        stageRepository.saveIndexingResult(
            runId, item.documentVersionId(), IngestionStageStatus.DEIDENTIFIED, result);
        contribution.incrementWriteCount(1);
      } catch (final IllegalArgumentException exception) {
        quarantine(runId, item, INVALID_STAGE_CODE, INVALID_STAGE_REASON);
        contribution.incrementProcessSkipCount();
      } catch (final IndexingException | ModelEmbeddingPermanentException exception) {
        quarantine(runId, item, INDEXING_FAILED_CODE, INDEXING_FAILED_REASON);
        contribution.incrementProcessSkipCount();
      }
    }
    return RepeatStatus.FINISHED;
  }

  private IndexingRequest requestFor(final DurableStageItem item) {
    if (item == null
        || item.deidentifiedIr() == null
        || item.processingStatus() == null
        || (item.processingStatus() != ProcessingStatus.SUCCEEDED
            && item.processingStatus() != ProcessingStatus.PARTIAL)
        || item.policyVersion() == null
        || item.policyVersion().isBlank()) {
      throw new IllegalArgumentException("stage payload is invalid");
    }
    final String title = safeMetadata(item.deidentifiedIr().metadata(), "title", item.sourceId());
    final String publisher = safeMetadata(item.deidentifiedIr().metadata(), "publisher", "Unknown");
    final ParseAndDeidentifyState state =
        new ParseAndDeidentifyState(
            workItem(item),
            item.deidentifiedIr(),
            item.phiTypeCounts() == null ? Map.of() : item.phiTypeCounts(),
            item.policyVersion(),
            List.of(),
            item.processingStatus(),
            FailureStage.NONE,
            "");
    return new IndexingRequest(
        state,
        title,
        configuration.chunkingStrategyId(),
        configuration.chunkingOptions(),
        new com.medassist.ingestion.context.ContextDocument(
            item.documentVersionId(),
            title,
            publisher,
            sharedDocumentSummary(item.deidentifiedIr())),
        configuration.contextualRetrievalMode(),
        configuration.contextPromptVersion(),
        configuration.embeddingModel());
  }

  private static String sharedDocumentSummary(final DocumentIR document) {
    final String explicit = document.metadata().get("summary");
    if (explicit != null && !explicit.isBlank()) {
      return bounded(explicit);
    }
    for (final Section section : document.sections()) {
      final String summary = firstSectionText(section);
      if (summary != null) {
        return bounded(summary);
      }
    }
    return "Summary unavailable";
  }

  private static String firstSectionText(final Section section) {
    if (!section.text().isBlank()) {
      return section.text();
    }
    for (final Section child : section.children()) {
      final String text = firstSectionText(child);
      if (text != null) {
        return text;
      }
    }
    return null;
  }

  private static String bounded(final String value) {
    final String normalized = value.trim().replaceAll("\\s+", " ");
    return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
  }

  private static IngestionWorkItem workItem(final DurableStageItem item) {
    final ObjectDescriptor descriptor =
        new ObjectDescriptor(
            item.sourceUri(),
            item.sourceId(),
            item.mimeType(),
            item.sizeBytes(),
            item.safeObjectMetadata(),
            () -> {
              throw new IOException("stage object stream is unavailable");
            });
    final Optional<String> previous = Optional.ofNullable(item.previousContentHash());
    return new IngestionWorkItem(
        new ObjectDiscoveryResult(
            descriptor, item.contentHash(), previous, item.classification(), true),
        item.logicalDocumentId(),
        item.documentVersionId());
  }

  private static String safeMetadata(
      final Map<String, String> metadata, final String key, final String fallback) {
    if (metadata == null) {
      return fallback;
    }
    final String value = metadata.get(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private void quarantine(
      final UUID runId,
      final DurableStageItem item,
      final String errorCode,
      final String safeReason) {
    stageRepository.quarantine(
        runId,
        item.documentVersionId(),
        IngestionStageStatus.DEIDENTIFIED,
        QuarantineStage.INDEXING,
        errorCode,
        safeReason);
  }

  private static UUID runId(final ChunkContext chunkContext) {
    try {
      final String value =
          chunkContext
              .getStepContext()
              .getStepExecution()
              .getJobExecution()
              .getExecutionContext()
              .getString(RUN_ID_CONTEXT_KEY);
      return UUID.fromString(value);
    } catch (final RuntimeException exception) {
      throw new IllegalStateException("ingestion run id is missing or invalid");
    }
  }
}
