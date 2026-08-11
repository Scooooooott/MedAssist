package com.medassist.ingestion.context.backfill;

import com.medassist.ingestion.context.ContextualChunk;
import com.medassist.ingestion.context.ContextualRetrievalMode;
import com.medassist.ingestion.context.ContextualRetrievalRequest;
import com.medassist.ingestion.context.ContextualRetrievalService;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingPort;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingRequest;
import com.medassist.ingestion.pipeline.index.BatchEmbeddingResponse;
import com.medassist.ingestion.pipeline.index.EmbeddingInput;
import com.medassist.ingestion.pipeline.index.EmbeddingModel;
import com.medassist.ingestion.pipeline.index.EmbeddingVector;
import com.medassist.ingestion.pipeline.index.PhiScanStatus;
import com.medassist.ingestion.pipeline.scan.PhiDetectionException;
import com.medassist.ingestion.pipeline.scan.PhiDetectionTransientException;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScan;
import com.medassist.ingestion.pipeline.scan.PostDeidentificationPhiScanner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Incrementally adds context and mode-specific embeddings without re-ingesting source documents.
 */
public final class ContextBackfillTasklet implements Tasklet {
  private final ContextBackfillRepository repository;
  private final ContextualRetrievalService contextualRetrievalService;
  private final BatchEmbeddingPort embeddingPort;
  private final PostDeidentificationPhiScanner phiScanner;
  private final EmbeddingModel embeddingModel;
  private final ContextualRetrievalMode mode;
  private final String promptVersion;
  private final Duration phiScanTimeout;
  private final int chunkLimit;

  public ContextBackfillTasklet(
      final ContextBackfillRepository repository,
      final ContextualRetrievalService contextualRetrievalService,
      final BatchEmbeddingPort embeddingPort,
      final PostDeidentificationPhiScanner phiScanner,
      final EmbeddingModel embeddingModel,
      final ContextualRetrievalMode mode,
      final String promptVersion,
      final Duration phiScanTimeout,
      final int chunkLimit) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.contextualRetrievalService =
        Objects.requireNonNull(contextualRetrievalService, "contextualRetrievalService");
    this.embeddingPort = Objects.requireNonNull(embeddingPort, "embeddingPort");
    this.phiScanner = Objects.requireNonNull(phiScanner, "phiScanner");
    this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
    this.phiScanTimeout = Objects.requireNonNull(phiScanTimeout, "phiScanTimeout");
    if (promptVersion.isBlank()
        || phiScanTimeout.isZero()
        || phiScanTimeout.isNegative()
        || chunkLimit <= 0) {
      throw new IllegalArgumentException("context backfill settings are invalid");
    }
    this.chunkLimit = chunkLimit;
  }

  @Override
  public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext)
      throws Exception {
    if (mode == ContextualRetrievalMode.OFF) {
      return RepeatStatus.FINISHED;
    }
    final List<ContextBackfillDocument> pending =
        repository.findPending(mode, promptVersion, chunkLimit);
    for (final ContextBackfillDocument document : pending) {
      backfillDocument(contribution, document);
    }
    return RepeatStatus.FINISHED;
  }

  private void backfillDocument(
      final StepContribution contribution, final ContextBackfillDocument document)
      throws Exception {
    final List<ContextualChunk> contextualChunks =
        contextualRetrievalService
            .prepare(
                new ContextualRetrievalRequest(
                    document.document(), mode, promptVersion, document.chunks()))
            .chunks();
    final List<ContextualChunk> cleanChunks = new ArrayList<>();
    for (final ContextualChunk chunk : contextualChunks) {
      contribution.incrementReadCount();
      final PostDeidentificationPhiScan scan = scan(chunk);
      if (scan.status() != PhiScanStatus.CLEAN) {
        repository.enqueuePhiReview(chunk.chunk().id(), scan.status(), scan.entityTypes());
        contribution.incrementProcessSkipCount();
      } else {
        cleanChunks.add(chunk);
      }
    }
    if (cleanChunks.isEmpty()) {
      return;
    }

    final List<EmbeddingInput> inputs =
        cleanChunks.stream()
            .map(chunk -> new EmbeddingInput(chunk.chunk().id(), chunk.embeddingText()))
            .toList();
    final BatchEmbeddingResponse response =
        embeddingPort.embed(new BatchEmbeddingRequest(embeddingModel, inputs));
    validateResponse(response, inputs.size());
    final List<ContextBackfillChunkWrite> writes = new ArrayList<>();
    for (int index = 0; index < cleanChunks.size(); index++) {
      writes.add(
          new ContextBackfillChunkWrite(
              cleanChunks.get(index).chunk().id(),
              cleanChunks.get(index).contextPrefix(),
              response.vectors().get(index).values()));
    }
    repository.save(new ContextBackfillWrite(mode, promptVersion, embeddingModel, writes));
    contribution.incrementWriteCount(writes.size());
  }

  private PostDeidentificationPhiScan scan(final ContextualChunk chunk) throws Exception {
    try {
      return phiScanner.scan(List.of(chunk.originalText(), chunk.contextPrefix()), phiScanTimeout);
    } catch (final PhiDetectionTransientException exception) {
      throw exception;
    } catch (final PhiDetectionException exception) {
      return new PostDeidentificationPhiScan(PhiScanStatus.FAILED, java.util.Set.of());
    }
  }

  private void validateResponse(final BatchEmbeddingResponse response, final int expectedCount) {
    if (response == null
        || !embeddingModel.name().equals(response.modelName())
        || !embeddingModel.version().equals(response.modelVersion())
        || embeddingModel.dimension() != response.dimension()
        || response.vectors().size() != expectedCount) {
      throw new IllegalStateException("context embedding response violates the model contract");
    }
    for (final EmbeddingVector vector : response.vectors()) {
      if (vector.values().size() != embeddingModel.dimension()) {
        throw new IllegalStateException("context embedding response violates the model contract");
      }
    }
  }
}
