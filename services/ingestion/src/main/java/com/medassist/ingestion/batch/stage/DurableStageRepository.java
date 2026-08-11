package com.medassist.ingestion.batch.stage;

import com.medassist.domain.DocumentIR;
import com.medassist.ingestion.pipeline.index.IndexingResult;
import com.medassist.ingestion.pipeline.model.ProcessingStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DurableStageRepository {
  void upsertDiscovered(DiscoveredStageItem item);

  List<DurableStageItem> findByRunAndState(UUID ingestionRunId, IngestionStageStatus state);

  void saveDeidentified(
      UUID ingestionRunId,
      UUID documentVersionId,
      IngestionStageStatus expectedState,
      DocumentIR deidentifiedIr,
      Map<String, Integer> phiTypeCounts,
      String policyVersion,
      ProcessingStatus processingStatus);

  void saveIndexingResult(
      UUID ingestionRunId,
      UUID documentVersionId,
      IngestionStageStatus expectedState,
      IndexingResult indexingResult);

  void markIndexed(UUID ingestionRunId, UUID documentVersionId, IngestionStageStatus expectedState);

  void quarantine(
      UUID ingestionRunId,
      UUID documentVersionId,
      IngestionStageStatus expectedState,
      QuarantineStage stage,
      String errorCode,
      String safeReason);
}
