package com.medassist.ingestion.batch.audit;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** Narrow persistence boundary for content-free ingestion audit data. */
public interface IngestionAuditRepository {
  UUID startRun(UUID runId, AuditRunParameters parameters);

  void finishRun(UUID runId, AuditRunStatus status);

  void updateStepAggregate(UUID runId, StepAggregate aggregate);

  void recordItem(UUID runId, AuditItem item);

  record AuditRunParameters(Instant requestedAt, String sourceScope, boolean forceReprocess) {
    public AuditRunParameters {
      if (requestedAt == null) {
        throw new IllegalArgumentException("requestedAt is required");
      }
      sourceScope = SafeAuditValues.requireSourceScope(sourceScope);
    }
  }

  record StepAggregate(
      AuditStep step, long readCount, long writeCount, long skipCount, long durationMs) {
    public StepAggregate {
      if (step == null) {
        throw new IllegalArgumentException("audit step is required");
      }
      if (readCount < 0 || writeCount < 0 || skipCount < 0 || durationMs < 0) {
        throw new IllegalArgumentException("audit aggregate values must be non-negative");
      }
    }
  }

  record AuditItem(
      UUID documentId,
      UUID documentVersionId,
      URI sourceUri,
      AuditStage stage,
      AuditItemStatus status,
      long durationMs,
      AuditFailure failure) {
    public AuditItem {
      if (sourceUri == null || stage == null || status == null || durationMs < 0) {
        throw new IllegalArgumentException("invalid audit item");
      }
      if ((status == AuditItemStatus.FAILED) != (failure != null)) {
        throw new IllegalArgumentException("audit failure must match item status");
      }
    }

    public static AuditItem succeeded(
        final UUID documentId,
        final UUID documentVersionId,
        final URI sourceUri,
        final AuditStage stage,
        final long durationMs) {
      return new AuditItem(
          documentId,
          documentVersionId,
          sourceUri,
          stage,
          AuditItemStatus.SUCCEEDED,
          durationMs,
          null);
    }

    public static AuditItem failed(
        final UUID documentId,
        final UUID documentVersionId,
        final URI sourceUri,
        final AuditStage stage,
        final long durationMs,
        final AuditFailure failure) {
      return new AuditItem(
          documentId,
          documentVersionId,
          sourceUri,
          stage,
          AuditItemStatus.FAILED,
          durationMs,
          failure);
    }
  }

  enum AuditRunStatus {
    COMPLETED,
    FAILED,
    STOPPED,
    ABANDONED,
    UNKNOWN
  }

  enum AuditItemStatus {
    SUCCEEDED,
    FAILED
  }

  enum AuditStep {
    DISCOVER_DOCUMENTS("discoverDocumentsStep", AuditStage.DISCOVERY),
    PARSE_AND_DEIDENTIFY("parseAndDeidentifyStep", AuditStage.PARSE_AND_DEIDENTIFY),
    CHUNK_AND_EMBED("chunkAndEmbedStep", AuditStage.CHUNK_AND_EMBED),
    INDEX("indexStep", AuditStage.INDEX);

    private final String batchStepName;
    private final AuditStage stage;

    AuditStep(final String batchStepName, final AuditStage stage) {
      this.batchStepName = batchStepName;
      this.stage = stage;
    }

    public String batchStepName() {
      return batchStepName;
    }

    public AuditStage stage() {
      return stage;
    }

    public static AuditStep fromBatchStepName(final String name) {
      for (final AuditStep step : values()) {
        if (step.batchStepName.equals(name)) {
          return step;
        }
      }
      throw new AuditPersistenceException("unknown ingestion step");
    }
  }

  enum AuditStage {
    DISCOVERY,
    PARSE_AND_DEIDENTIFY,
    CHUNK_AND_EMBED,
    INDEX
  }

  enum AuditFailure {
    DISCOVERY_FAILED("DISCOVERY_FAILED", "Discovery stage failed"),
    PARSE_FAILED("PARSE_FAILED", "Parsing stage failed"),
    DEIDENTIFICATION_FAILED("DEIDENTIFICATION_FAILED", "De-identification stage failed"),
    PHI_RESCAN_FAILED("PHI_RESCAN_FAILED", "Post-de-identification scan failed"),
    EMBEDDING_FAILED("EMBEDDING_FAILED", "Embedding stage failed"),
    INDEXING_FAILED("INDEXING_FAILED", "Indexing stage failed"),
    PERSISTENCE_FAILED("PERSISTENCE_FAILED", "Persistence stage failed");

    private final String errorCode;
    private final String safeReason;

    AuditFailure(final String errorCode, final String safeReason) {
      this.errorCode = errorCode;
      this.safeReason = safeReason;
    }

    public String errorCode() {
      return errorCode;
    }

    public String safeReason() {
      return safeReason;
    }
  }
}
