package com.medassist.ingestion.batch.stage;

import com.medassist.domain.DocumentIR;
import com.medassist.ingestion.discovery.DiscoveryClassification;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/** One safely restorable stage row. Payloads are present only after their corresponding stage. */
public record DurableStageItem(
    UUID ingestionRunId,
    UUID logicalDocumentId,
    UUID documentVersionId,
    URI sourceUri,
    String sourceId,
    String mimeType,
    long sizeBytes,
    String contentHash,
    String previousContentHash,
    DiscoveryClassification classification,
    Map<String, String> safeObjectMetadata,
    boolean forceReprocess,
    IngestionStageStatus status,
    DocumentIR deidentifiedIr,
    Map<String, Integer> phiTypeCounts,
    String policyVersion,
    ProcessingStatus processingStatus,
    IndexingResult indexingResult,
    QuarantineStage quarantineStage,
    String errorCode,
    String safeReason) {}
